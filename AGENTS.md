# AGENTS.md: integrating with the TradeLocker Brand Socket

This file is for AI coding agents (and the humans pairing with them) that build or review a Brand
Socket integration. It is written from what went wrong in real integrations. If you are a brand
developer, point your agent at this repository and ask it to **check your integration against
this file**; every rule below is observable from your side and most of them are testable in five
minutes against the development environment.

The contract itself is `docs/asyncapi.yaml` (AsyncAPI 3.0; rendered at
https://tradelocker.github.io/brand-socket-api-docs/). Reference clients that follow every rule
here: [`examples/node`](examples/node), [`examples/python`](examples/python),
[`examples/java`](examples/java). When this file and the contract disagree, the contract wins.

## 1. The model in one paragraph

One Socket.IO v4 connection per **brand API key and account type** (`LIVE` or `DEMO`) carries every
account of the brand. The server answers the handshake on the `connection` event
(`{"status","code","message"}`), then sends a **snapshot** on the `stream` event (every
`AccountStatus`, then every `Position`, then every `OpenOrder`, then `Property SyncEnd`), then
live updates in engine order. Quotes are opt-in through the `subscriptions` event. There is no
per-account subscription, no REST fallback on this socket, and no replay: a reconnect starts a new
snapshot.

## 2. Connection rules the server enforces

| Rule | What you see when you break it | Advice |
|---|---|---|
| One connection per key and type **per server instance** | The older socket gets `{"code":"disconnected","message":"Replaced by new connection with the same API key."}` and is closed | Run one process per key and type. Two instances sharing a key replace each other forever; one integrator did this every 10 s for a day without noticing. Treat `disconnected` as "stop", not "reconnect". |
| `LIVE` and `DEMO` are separate connections | Mixing accounts of both types on one socket is not possible | Two connections, same key is fine (since contract 1.3.0). Connect with a type the brand is set up for, or you get `badRequest`. |
| Connection guard: 10 handshakes per 2 minutes per brand and type | `conflict.<environment>-<type>-<brand>` and the socket is closed | Back off exponentially (1 s, 2 s, 4 s … capped at 30 s, with jitter). A reconnect loop that burns the budget locks you out for two minutes and shows up in TradeLocker's logs as a storm (one integrator produced 452 reconnects in 8 s). |
| Key scope | `forbidden.brandToken.cannotEstablishConnectionUsingRestrictedApiKey` | The key must have scope `FULL_ACCESS` or `SOCKET`. `READ_WRITE` and `READONLY` are REST-only. |
| Socket not enabled for the brand | `unauthorized.brandSocketDisabled` | TradeLocker enables it per brand. |
| Wrong environment | `forbidden.brand-not-found` | Development keys only work on the development hosts and vice versa. |
| Transport | Handshake fails before any `connection` event | `websocket` only, path `/brand-api/socket.io`, namespace `/brand-socket`. Browsers cannot set the `brand-api-key` header, so connect from a backend. Never put the key in the URL. |
| Handshake deadline | The socket closes without `connected` after 30 s | The server opens the brand's trading session during the handshake (1-7 s normally). Treat a silent close as a failed handshake and back off. |

## 3. Reconnect decision table

Socket.IO clients reconnect on their own after a **network** loss or a ping timeout, but **not**
after the server closes the socket (client-side reason `io server disconnect`). The Brand Socket
closes server-side in every case below, so your code decides:

| Last `connection` status before the close | Do |
|---|---|
| `shutdown` | Reconnect after 1-5 s. A deploy: the pod told every client goodbye and closes 500 ms later; a new pod is already serving. Expect a full snapshot. |
| `connectionError` | Reconnect with backoff. The trading session behind your socket ended (trade engine restart or node loss). |
| `disconnected` | **Do not reconnect automatically.** Another connection holds your key, or TradeLocker closed you on purpose. Alert a human. |
| any handshake error (`badRequest*`, `forbidden*`, `unauthorized*`) | Do not retry the same request: fix the cause. |
| `conflict.*`, `internalServer.*`, `unknown` | Retry with backoff, bounded. |
| none (closed without a status) | Treat as a failed handshake or a network loss: reconnect with backoff. If it keeps happening within seconds, you are being replaced by another instance. |

Set `reconnection: false` (or the equivalent) in the client and own the policy; the examples show
one. Set a descriptive `User-Agent` header: TradeLocker logs it on every refused handshake and it
is how support finds your client among the others.

## 4. Snapshot and state

- **Rebuild, do not merge.** Clear your account, position and order state when a connection opens
  and rebuild it from the snapshot. A position closed while you were disconnected has no
  `ClosePosition` for you; it is simply absent from the new snapshot.
- **Order and size.** All `AccountStatus`, then all `Position`, then all `OpenOrder`, then
  `Property SyncEnd`. A brand with 5,000 accounts gets ~7,000 messages (about 3 MB of JSON) within
  a second or two; a 60,000-account brand gets ten times that. Parse and store before you do anything
  slow per message, and never block the socket's event loop with per-message I/O.
- **Keep reading.** A client that stops reading while the server has frames queued for it is cut
  after 10 s (status reason `slow_consumer` on TradeLocker's side; you see a close without a status
  or a ping timeout, depending on the proxy in front). It may reconnect for a fresh snapshot, so
  back-pressure your own pipeline instead of the socket.
- `SyncEnd` is the moment your state is complete. Compute aggregates, start risk checks and send
  quote subscriptions from there (subscriptions sent before it are parked and answered after it).
- Expect a reconnect every so often even without a deploy (node maintenance). Your state machine
  must survive "snapshot, SyncEnd, updates, close, snapshot, SyncEnd…" indefinitely.

## 5. Message semantics and edge cases

**All messages**
- Figures are decimal **strings** (`"10628.95"`). Parse with `Decimal`/`BigDecimal`, never
  `float`/`double`: equity and PnL are compared for exact thresholds. IDs are strings and exceed
  2^53, so never coerce them to JavaScript numbers.
- Fields without a value are **omitted**, not `null`. Unknown fields and unknown `type` values must
  be ignored; both are added without notice.
- Timestamps are ISO-8601 UTC with milliseconds (`2026-10-22T19:55:09.117Z`).
- `accountId` carries the type prefix: `L#` live, `D#` demo. The number after it is the trading
  account id you see in the Brand REST API and the Back Office.

**AccountStatus**
- Partial by design: an update carries the figures the engine changed (`equity`, `marginAvailable`,
  `marginUsed`, `positionPnLs`…) and omits the rest. Merge into the last status of that account.
  The snapshot's status is complete.
- `positionPnLs` is the unrealised PnL per open position of the account; use it rather than
  recomputing PnL from quotes.
- An account whose Back Office setup is incomplete (no asset balance group) **never appears** and
  its updates are dropped server-side. If accounts you expect are missing from the snapshot, check
  their Assets setup in the Back Office before suspecting the socket.
- A **margin stop-out** is not a message of its own: `marginAvailable` turns negative on
  `AccountStatus`, then one or more `ClosePosition` follow. If your risk engine needs the reason,
  take it from the Brand REST API's order history, not from the socket.
- Update rate: at most about one `AccountStatus` per second per account while its instruments tick;
  a brand with thousands of accounts holding positions on a busy instrument sees hundreds of
  statuses per second. Size your consumer for it.

**Position**
- `openOrderId` is omitted when the position has no opening order id. `stopLossLimit`,
  `takeProfitLimit`, `maintMargin`, `fee` and `swaps` are `"0"` when not applicable.
- A `Position` message can repeat with the same `positionId` (partial close, SL/TP change): replace
  the stored one.

**ClosePosition**
- May name a `positionId` you have never seen (opened and closed between your snapshots, or while
  you were catching up). Tolerate it. `closePrice` is omitted when the engine did not report one.

**OpenOrder**
- Canceled and refused orders are **not** streamed. An order that disappears from your view
  without a final message was canceled; confirm through REST if you need the reason.
- `isOpen` false marks a fill; `lastUpdate` is when the message was produced, not the fill time.
- `positionId` appears only once the order has a position (a pending entry order has none).
- Orders whose price the engine reports as not-a-number are dropped; a `slPrice`/`tpPrice` that is
  not a number is omitted.
- Enumerations grow: `orderType` includes `MANUAL`, `status` includes `EXEC_TYPE_ACTIVATED`. Treat
  enums as open sets.

**Property**
- Only `SyncEnd` exists today; more may come. Branch on `name`.

## 6. Quotes

- Emit `subscriptions` with `{"action":"SUBSCRIBE","instrument":"EURUSD"}` **and an ack callback**.
  The callback receives `{"status","code","message","instrument"}`: `subscribed` / `unsubscribed`
  on success, `validation.badRequest`, `notFound` (not an instrument of your brand), `notImplemented`
  (no quote feed for your brand's environment), `unknown` otherwise. Without a callback, success is
  silent and errors arrive on `connection`.
- Instrument names are exact and case-sensitive, as your brand sees them.
- Quotes arrive on the `subscriptions` event as `{"type":"Quote","instrument","routeId",
  "timestamp","bid","ask"}`, one per price change and per route of the instrument. Subscriptions do
  not survive a reconnect: subscribe again after `SyncEnd`.
- `notImplemented` is an environment property, not an error you can fix: not every brand environment
  has a quote feed. Fall back to your own price source.

## 7. Operating it

- Log every `connection` event with its `code`, and count reconnects per hour. One `conflict.*` or
  more than ~4 connects per hour outside deploys is a bug on your side.
- Keep the connection behind an idle-tolerant path: the server pings every 25 s and closes after
  20 s without a pong; a corporate proxy that cuts idle TCP at 60 s will look like random drops.
- Expect `shutdown` a few times a week (releases roll pods one by one; every client reconnects once
  onto the new pod). Dev restarts more often than production.
- Development hosts: `https://api-dev.tradelocker.com` (shared edge) and
  `https://brand-socket-api-dev-1.tradelocker.com` (direct). Production: `https://api.tradelocker.com`,
  or the dedicated hostname TradeLocker gives your brand. Same contract everywhere.

## 8. Checklist for an agent reviewing an integration

Run through the code and tick each item; for the behavioural ones, test against development.

1. Exactly one process holds each (key, type) pair; the deployment cannot start two replicas.
2. `reconnection` is disabled in the client and the policy of section 3 is implemented: no automatic
   reconnect on `disconnected`, bounded backoff elsewhere, no retry on handshake errors.
3. State is cleared on connect and rebuilt up to `SyncEnd`; `ClosePosition` for an unknown position
   does not throw.
4. `AccountStatus` updates are merged into the previous status; missing fields keep their value.
5. Figures parsed as decimals, IDs kept as strings, unknown `type`s and fields ignored.
6. Quote subscriptions use the ack callback and are re-sent after every `SyncEnd`.
7. A `User-Agent` identifies the integration; the key is only in the handshake header.
8. Behavioural test: start a second instance with the same key and confirm the first stops (does
   not reconnect) after `disconnected`. Then kill the network for 30 s and confirm exactly one
   reconnect with a new snapshot.
9. Behavioural test: subscribe before `SyncEnd` and confirm the ack arrives after it; subscribe to
   a misspelled instrument and confirm `notFound` without a disconnect.
10. Compare the snapshot's account count with the brand's account count in the Back Office; investigate
    missing accounts in their Assets setup before filing a ticket.

## 9. Asking TradeLocker for help

Send: brand id, environment, account type, the UTC time window, the `connection` codes you received,
your `User-Agent`, and the client's public IP. With those, the server-side log of your handshakes
and session ends is one query away.
