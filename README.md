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

## Before you write code

1. **One connection per API key and type.** A second connection with the same key replaces the first.
2. **Connect with a `type` your brand is set up for**, or the handshake is refused with `badRequest`.
3. **Back off.** More than 10 connection attempts in 2 minutes are refused with `conflict.*`.
4. **Reconnect yourself after a server-side close** (`shutdown`, `connectionError`): Socket.IO does not.
5. **Rebuild state from each new snapshot** (everything up to `Property` `SyncEnd`).
6. **Decimal strings, not floats; IDs are strings.** Fields with no value are omitted.

Every result code, with what to do about it, is in the docs under the `connection` channel.

## About this repository

`docs/` is the GitHub Pages site. It is generated from the AsyncAPI spec maintained with the
Brand Socket service; do not edit it by hand.
