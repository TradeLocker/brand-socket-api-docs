// Reference Brand Socket client for Node.js (socket.io-client 4.x).
//
//   BRAND_API_KEY=... node brand-socket.mjs                      # development host by default
//   BRAND_SOCKET_URL=https://api.tradelocker.com ...             # production
//   RUN_SECONDS=30 INSTRUMENTS=EURUSD ...                        # exit after 30 s; subscribe to quotes
//
// What it gets right, in the order integrations usually get it wrong:
//   1. one process per API key and account type; a `disconnected` status means another consumer
//      took the key, so this process stops instead of fighting it;
//   2. reconnects only on `shutdown` (deploy) and `connectionError` (trading session lost), with a
//      delay and a cap, never in a tight loop (10 attempts per 2 minutes per brand are refused);
//   3. rebuilds state from each snapshot up to `Property SyncEnd` instead of merging it;
//   4. keeps figures as decimal strings and IDs as strings;
//   5. quote subscriptions with an acknowledgement callback, sent after `connected`.
import { io } from "socket.io-client";

const URL = process.env.BRAND_SOCKET_URL ?? "https://brand-socket-api-dev-1.tradelocker.com"; // development (direct host); production: your dedicated hostname or https://api.tradelocker.com
const KEY = process.env.BRAND_API_KEY;
const TYPE = process.env.ACCOUNT_TYPE ?? "LIVE";
const INSTRUMENTS = (process.env.INSTRUMENTS ?? "").split(",").filter(Boolean); // e.g. EURUSD,XAUUSD
if (!KEY) { console.error("BRAND_API_KEY is required"); process.exit(2); }
const RUN_SECONDS = Number(process.env.RUN_SECONDS ?? 0); // 0: run until killed

// State: everything keyed by account, rebuilt from every snapshot.
const state = { accounts: new Map(), positions: new Map(), orders: new Map(), synced: false, connectedAt: 0 };

function applyStream(message) {
  switch (message.type) {
    case "AccountStatus": {
      // Partial update: fields the server did not send keep their previous value.
      const previous = state.accounts.get(message.accountId) ?? {};
      state.accounts.set(message.accountId, { ...previous, ...message });
      break;
    }
    case "Position":
      state.positions.set(message.positionId, message);
      break;
    case "ClosePosition":
      // May name a position this process never saw (opened and closed while disconnected).
      state.positions.delete(message.positionId);
      break;
    case "OpenOrder":
      if (message.isOpen === false) state.orders.delete(message.orderId);
      else state.orders.set(message.orderId, message);
      break;
    case "Property":
      if (message.name === "SyncEnd") {
        state.synced = true;
        const seconds = ((Date.now() - state.connectedAt) / 1000).toFixed(1);
        console.log(`SyncEnd after ${seconds} s: ${state.accounts.size} accounts, ${state.positions.size} positions, ${state.orders.size} open orders`);
      }
      break;
    default:
      // New message types can appear; ignore what you do not know.
      break;
  }
}

let attempt = 0;
let stopped = false;

function connect() {
  state.accounts.clear(); state.positions.clear(); state.orders.clear(); state.synced = false;
  const socket = io(`${URL}/brand-socket`, {
    path: "/brand-api/socket.io",
    transports: ["websocket"],          // the server offers no long-polling
    query: { type: TYPE },
    extraHeaders: {
      "brand-api-key": KEY,              // never in the URL
      "user-agent": "my-brand-integration/1.0 (ops@example.com)", // shows up in TradeLocker's logs when a handshake is refused
    },
    reconnection: false,                 // we decide when to reconnect (see below)
  });

  socket.on("connection", (status) => {
    console.log("connection", JSON.stringify(status));
    if (status.code === "connected") {
      attempt = 0;
      state.connectedAt = Date.now();
      for (const instrument of INSTRUMENTS) {
        // The ack answers every request; a request sent before SyncEnd is answered after it.
        socket.emit("subscriptions", { action: "SUBSCRIBE", instrument }, (ack) => console.log("subscribe", JSON.stringify(ack)));
      }
      return;
    }
    if (status.status === "error" && !state.synced && status.code !== "connectionError") {
      // Handshake refused: the server closes the socket. Fix the cause; retrying the same request
      // only burns the 10-per-2-minutes budget. Exceptions worth a bounded retry: conflict.*,
      // internalServer.*, unknown.
      const retryable = /^(conflict|internalServer|unknown)/.test(status.code);
      if (!retryable) { console.error("handshake refused, not retrying:", status.code); stopped = true; }
      return;
    }
    if (status.code === "disconnected") {
      // Another connection with this key and type replaced us (or TradeLocker closed us). Stop:
      // reconnecting here makes two consumers replace each other forever.
      console.error("replaced or closed by the server; not reconnecting");
      stopped = true;
    }
    // `shutdown` and `connectionError` are followed by the disconnect below, which reconnects.
  });

  socket.on("stream", applyStream);
  socket.on("subscriptions", (quote) => console.log("quote", JSON.stringify(quote)));

  socket.on("disconnect", (reason) => {
    console.log("disconnect", reason);
    if (stopped) return;
    // Both a server-side close (`io server disconnect`, after `shutdown` or `connectionError`) and
    // a network loss end up here. One reconnect with growing delay, capped so a dead server never
    // sees more than ~5 attempts in 2 minutes from this process.
    attempt += 1;
    const delayMs = Math.min(30_000, 1_000 * 2 ** Math.min(attempt, 5)) + Math.floor(Math.random() * 1_000);
    console.log(`reconnecting in ${delayMs} ms (attempt ${attempt})`);
    setTimeout(connect, delayMs);
  });

  socket.on("connect_error", (error) => console.error("connect_error", error.message));
}

connect();
if (RUN_SECONDS > 0) {
  setTimeout(() => {
    console.log(`done after ${RUN_SECONDS} s: ${state.accounts.size} accounts, ${state.positions.size} positions, ${state.orders.size} open orders, synced=${state.synced}`);
    process.exit(0);
  }, RUN_SECONDS * 1000).unref?.();
}
