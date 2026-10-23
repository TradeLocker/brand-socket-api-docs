"""Reference Brand Socket client for Python (python-socketio 5.x, asyncio).

    BRAND_API_KEY=... BRAND_SOCKET_URL=https://api-dev.tradelocker.com ACCOUNT_TYPE=LIVE python brand_socket.py

The rules it follows are the ones integrations break most often: one process per API key and
account type (a `disconnected` status means another consumer took the key, so this process stops);
reconnect only after `shutdown` or `connectionError`, with a delay (10 attempts per 2 minutes per
brand are refused); rebuild state from every snapshot up to `Property SyncEnd`; keep figures as
Decimal and IDs as strings; subscribe to quotes with an acknowledgement, after `connected`.
"""
import asyncio
import os
import random
import re
from decimal import Decimal

import socketio

URL = os.environ.get("BRAND_SOCKET_URL", "https://api.tradelocker.com")
KEY = os.environ["BRAND_API_KEY"]
TYPE = os.environ.get("ACCOUNT_TYPE", "LIVE")
INSTRUMENTS = [i for i in os.environ.get("INSTRUMENTS", "").split(",") if i]
NAMESPACE = "/brand-socket"

RETRYABLE_HANDSHAKE = re.compile(r"^(conflict|internalServer|unknown)")


class State:
    def __init__(self) -> None:
        self.accounts: dict[str, dict] = {}
        self.positions: dict[str, dict] = {}
        self.orders: dict[str, dict] = {}
        self.synced = False

    def apply(self, message: dict) -> None:
        kind = message.get("type")
        if kind == "AccountStatus":
            # Partial update: keep what the server did not resend.
            merged = {**self.accounts.get(message["accountId"], {}), **message}
            self.accounts[message["accountId"]] = merged
        elif kind == "Position":
            self.positions[message["positionId"]] = message
        elif kind == "ClosePosition":
            # May name a position this process never saw.
            self.positions.pop(message["positionId"], None)
        elif kind == "OpenOrder":
            if message.get("isOpen") is False:
                self.orders.pop(message["orderId"], None)
            else:
                self.orders[message["orderId"]] = message
        elif kind == "Property" and message.get("name") == "SyncEnd":
            self.synced = True
            print(f"SyncEnd: {len(self.accounts)} accounts, {len(self.positions)} positions, {len(self.orders)} open orders")
        # Unknown types are ignored on purpose: new ones can appear.


def equity(status: dict) -> Decimal | None:
    """Figures are decimal strings; never parse them as float."""
    value = status.get("equity")
    return Decimal(value) if value is not None else None


async def main() -> None:
    attempt = 0
    while True:
        state = State()
        stop = asyncio.Event()
        reconnect = asyncio.Event()
        sio = socketio.AsyncClient(reconnection=False)  # we decide when to reconnect

        @sio.on("connection", namespace=NAMESPACE)
        async def on_connection(status):
            nonlocal attempt
            print("connection", status)
            code = status.get("code", "")
            if code == "connected":
                attempt = 0
                for instrument in INSTRUMENTS:
                    ack = await sio.call("subscriptions", {"action": "SUBSCRIBE", "instrument": instrument}, namespace=NAMESPACE, timeout=30)
                    print("subscribe", ack)
                return
            if status.get("status") == "error" and not state.synced and code != "connectionError":
                if not RETRYABLE_HANDSHAKE.match(code):
                    print("handshake refused, not retrying:", code)
                    stop.set()
                return
            if code == "disconnected":
                print("replaced or closed by the server; not reconnecting")
                stop.set()

        @sio.on("stream", namespace=NAMESPACE)
        async def on_stream(message):
            state.apply(message)

        @sio.on("subscriptions", namespace=NAMESPACE)
        async def on_quote(quote):
            print("quote", quote)

        @sio.event(namespace=NAMESPACE)
        async def disconnect():
            print("disconnect")
            reconnect.set()

        try:
            await sio.connect(
                f"{URL}?type={TYPE}",  # account type as a query parameter
                namespaces=[NAMESPACE],
                socketio_path="/brand-api/socket.io",
                transports=["websocket"],
                headers={"brand-api-key": KEY, "User-Agent": "my-brand-integration/1.0 (ops@example.com)"},
                auth=None,
                wait_timeout=30,
            )
        except socketio.exceptions.ConnectionError as error:
            print("connect error", error)
            reconnect.set()

        await asyncio.wait([asyncio.create_task(stop.wait()), asyncio.create_task(reconnect.wait())], return_when=asyncio.FIRST_COMPLETED)
        await sio.disconnect()
        if stop.is_set():
            return
        attempt += 1
        delay = min(30.0, 2.0 ** min(attempt, 5)) + random.random()
        print(f"reconnecting in {delay:.1f} s (attempt {attempt})")
        await asyncio.sleep(delay)


if __name__ == "__main__":
    asyncio.run(main())
