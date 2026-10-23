# TradeLocker Brand Socket API

Real-time updates for every account of your brand over [Socket.IO](https://socket.io/): account
status (balance, equity, margin, per-position PnL), positions, closed positions and orders, plus
instrument quotes on request. One connection per brand and account type, authenticated with your
brand API key.

| | |
|---|---|
| **Docs** | https://tradelocker.github.io/brand-socket-api-docs/ |
| **Contract** | [`docs/asyncapi.yaml`](docs/asyncapi.yaml) · [JSON](docs/asyncapi.json) (AsyncAPI 3.0) |
| **Brand REST API** | https://brand-docs.tradelocker.com/ |

## Connect

| Environment | Socket.IO base URL |
|---|---|
| Production | `https://api.tradelocker.com/brand-socket` |
| Development | `https://api-dev.tradelocker.com/brand-socket` or `https://brand-socket-api-dev-1.tradelocker.com/brand-socket` |

Socket.IO options in every environment: `path: "/brand-api/socket.io"`, `transports: ["websocket"]`,
header `brand-api-key`, query `type=LIVE` or `type=DEMO`.

```js
import { io } from 'socket.io-client';

const socket = io('https://api.tradelocker.com/brand-socket', {
  path: '/brand-api/socket.io',
  transports: ['websocket'],
  query: { type: 'LIVE' },
  extraHeaders: { 'brand-api-key': process.env.BRAND_API_KEY },
});

socket.on('connection', (status) => console.log('connection', status)); // {status, code, message}
socket.on('stream', (message) => console.log('stream', message));       // snapshot, SyncEnd, live updates
```

## These docs are AI-agent ready

Point your coding agent (Claude Code, Codex, Cursor, Copilot…) at this repository and ask it to
**build or check your integration against [`AGENTS.md`](AGENTS.md)**. That file holds the rules
the server enforces, the reconnect decision table, every edge case we have seen in live
integrations, and a ten-point review checklist. The contract it refers to is
[`docs/asyncapi.yaml`](docs/asyncapi.yaml); a prompt that works:

> Read AGENTS.md and docs/asyncapi.yaml in https://github.com/TradeLocker/brand-socket-api-docs,
> then review my Brand Socket client against the checklist in AGENTS.md section 8 and list what
> to fix.

## Examples

Reference clients that follow every rule in `AGENTS.md`: connect, keep state from the snapshot,
reconnect correctly, subscribe to quotes with acknowledgements. One script runs any of them and
installs what it needs on first use:

```bash
export BRAND_API_KEY=...            # or put BRAND_API_KEY=... in examples/.env (git-ignored)
examples/run.sh node 30             # Node.js, exit after 30 s
examples/run.sh python              # Python (asyncio), run until Ctrl-C
INSTRUMENTS=EURUSD examples/run.sh java 60
```

| Language | Library | Entry point |
|---|---|---|
| [Node.js](examples/node) | `socket.io-client` 4.x | `examples/node/brand-socket.mjs` |
| [Python](examples/python) | `python-socketio` 5.x (asyncio) | `examples/python/brand_socket.py` |
| [Java](examples/java) | `io.socket:socket.io-client` 2.x, Maven | `examples/java/.../BrandSocketClient.java` |

The **direct development host is the default** (`https://brand-socket-api-dev-1.tradelocker.com`);
set `BRAND_SOCKET_URL` to the dedicated hostname TradeLocker gave your brand, or
`https://api.tradelocker.com`, for production. Prefer the direct or dedicated hostname over the
shared `api-dev`/`api` edge: measured from the same machine, a 7,000-message snapshot took 0.5 s on
the direct host every time and anywhere between 0.6 s and 17 s through the shared edge. Other knobs: `ACCOUNT_TYPE`
(`LIVE`, default, or `DEMO`), `INSTRUMENTS` (comma-separated, for quotes), `RUN_SECONDS` (exit
after that long with a summary; default runs until Ctrl-C). A 30 s run against development with a
brand key prints `connection … connected`, the quote acknowledgement (`notImplemented` where the
environment has no quote feed) and `SyncEnd: <accounts> accounts, <positions> positions, <orders>
open orders`.

## Before you write code

1. **One connection per API key and account type.** A second connection with the same key and type
   replaces the first (`disconnected`); `LIVE` and `DEMO` with one key run side by side. Never share a
   key between processes.
2. **Connect with a `type` your brand is set up for**, or the handshake is refused with `badRequest`.
3. **Back off.** More than 10 connection attempts in 2 minutes are refused with `conflict.*`.
4. **Reconnect yourself after a server-side close** (`shutdown`, `connectionError`): Socket.IO does not.
   Do **not** reconnect after `disconnected`: another consumer holds your key.
5. **Rebuild state from each new snapshot** (everything up to `Property` `SyncEnd`).
6. **Decimal strings, not floats; IDs are strings.** Fields with no value are omitted.
7. **Subscribe to quotes with an acknowledgement callback**, after `connected`.

Every result code, with what to do about it, is in the docs under the `connection` channel; the
long version of every rule is in [`AGENTS.md`](AGENTS.md).

## About this repository

`docs/` is the GitHub Pages site. It is generated from the AsyncAPI spec maintained with the
Brand Socket service; do not edit it by hand. `AGENTS.md`, `README.md` and `examples/` are
maintained here.
