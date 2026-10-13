# Live engine

The live engine trades the same rules a backtest optimises, on the same candles, from Bitvavo's
WebSocket feed. It is **dry-run by default**: orders are recorded, never sent, until a strategy is
created with `dryRun: false` and a bot configuration whose credentials and operator ID it uses.

## The stream

One WebSocket connection per running strategy (`wss://ws.bitvavo.com/v2/`), subscribed to the
candle channel of its market and interval. Our own policies:

- **Keepalive**: a `getTime` action every 30 seconds (`strategy.live.keepalive-seconds`), because
  Bitvavo closes connections that stay idle.
- **Reconnect**: after any close or failure, with exponential backoff from 5 seconds
  (`strategy.live.reconnect-delay-millis`) capped at 60 seconds; a reconnect re-subscribes.
- **Boundaries only**: the stream reports *when* a candle closed (its open time); the candle data
  itself is always re-read from the candle store, whose REST backfill is authoritative. A
  trade-free interval therefore cannot be missed, and the live evaluation reads exactly the data a
  backtest would.
- **Errors**: a Bitvavo `error` event (a rejected subscription, for example) is logged at warn and
  kept as the stream's `lastError`, which `GET /api/live-strategies/{id}` returns. A rejected
  subscription leaves the connection up while no candle arrives, so this is how an operator sees
  why a strategy is silent.
- **Isolation**: boundaries are delivered on the stream's own single delivery thread, so a strategy
  evaluation (store reads, a retry, order placement) never blocks the socket's read loop or
  `stop()`.

## Deciding and trading

- The decision bar is the candle that just closed. The engine evaluates a **bounded window** of the
  strategy's history for its market and interval — the warm-up plus the MACD slow and signal
  periods plus a 1,000-candle convergence margin, seeded from the store — with the same rules,
  parameters and sizing as a backtest. The MACD/PSAR recursions converge long before that margin,
  so the decision at a bar matches what a whole-history evaluation produces there, without
  re-reading or re-computing a history that grows for the life of the process. The replay gate
  (`LiveSignalReplayTest`) pins both halves of that argument: one case asserts candle-by-candle
  (prefix) equality of the signals and the MACD/SAR values, the other asserts that the exact window
  the runner uses — front-truncated — still matches the whole-series values at its last bar.
- Signals inside the warm-up (`warmUpCandles`, default 200, as in a backtest) are ignored.
- **No stale replay**: only candles that close while the strategy runs are acted on. The seed
  itself is never traded, so downtime is not replayed; a candle that closes during a brief stream
  outage is still evaluated when the stream reconnects, and a boundary whose candle cannot be
  resolved is recorded as a `SKIPPED` row in the strategy's orders, its signal never replayed.
- **Fills**: a signal on a closed candle is filled immediately, at the open of the candle that just
  started — the price the exchange is trading at while the decision is made, which is the same
  next-open fill the backtester simulates. A live order goes out at that moment, so it and the
  price it records coincide; a dry-run fill records that open. When a boundary is announced late —
  a candle that closed during a stream outage — the fill price is the open of the candle that
  followed it, read from the store, so those fills keep the backtester's price too; a frame without
  a usable open falls back to the closed candle's close.
- **Pre-write**: before a real order is sent it is saved as a `PENDING` row carrying its client
  order ID (`clientOrderId`): a UUID derived from the strategy and a persisted per-strategy
  sequence (`ClientOrderIds`), sent to Bitvavo with the order and kept on the row. The derivation
  is deterministic so the boot reconciliation can re-derive its own possible IDs and recognise
  orders whose records were lost to a restore; Bitvavo requires the value to be unique only across
  open orders, so a sequence number whose order was never accepted (or has long filled) is safe to
  reuse. The persisted sequence is advanced before the pre-write, so a crash can skip a number but
  never let a later order reuse one.
  The same row is then updated in place — to `FILLED` with the exchange's amounts, price and fee, or
  to `FAILED` with the error — so an ambiguous timeout between the write and the response stays
  reconcilable at the next boot (see "Reconciliation on startup"). An order rejected before it is
  sent (no active bot, insufficient balance, below the market minimum, an exchange error before the
  pre-write) is recorded as `REJECTED` with no client order ID; dry-run rows carry none either.
- One position at a time: entry signals are ignored while a position is open, exit signals while
  flat.
- **Sizing**: a fixed EUR amount per buy (`amountPerBuy`, default 100, minimum 5 EUR), rounded down
  to the market's notional decimals for live orders; sells round the position quantity down to the
  market's quantity decimals. Only EUR-quoted markets are accepted, since the sizing, the balance
  check and the recorded amounts are all EUR. Simulated fees use the taker rate the backtester
  defaults to (0.20%); live fills record the exchange's `feePaid`.
- A rejected or failed order (no active bot configuration, insufficient balance, below the market
  minimum, an exchange error) is recorded with its reason, never thrown, so a running strategy
  survives it. Skipped buys stay distinguishable from plain no-signal bars through these records.

## State

- `live_strategies` — the configuration, `dryRun`, `enabled`, and `lastEvaluatedOpenTime`: the open
  time of the last evaluated candle. It is persisted **before** the order for that candle is
  placed, so a crash costs a trade instead of duplicating one, and evaluation is idempotent across
  restarts.
- `live_orders` — every intended or executed order: `PENDING` (written before a real order is sent),
  `DRY_RUN`, `REJECTED` (a local, pre-flight rejection that never reached the exchange), `FILLED` or
  `FAILED`, with the signal's candle open time, the rule set that fired, amounts, the fill or
  estimated price, the fee, the exchange order ID for live fills and the client order ID the
  pre-write sent. A boundary whose candle could not be resolved is recorded as `SKIPPED`, so a
  missed signal window is visible through the API.
- `live_positions` — the position, closed in place (`OPEN` → `CLOSED`) with the realised profit
  after fees.

On boot, every strategy with `enabled: true` is started again (`LiveStrategyStarter`), after
reconciliation has run. A strategy whose reconciliation failed is held back — `startEnabled` logs
it (`not started: reconciliation failed; restart after fixing`) and does not start it — so it
cannot trade on state that could not be trusted; the rest still start, and a failure to start one
never stops the others. Lifecycle transitions are serialized: two concurrent starts can never
create two runners for one strategy, and the enabled flag is written with a targeted field update,
so a concurrent last-evaluated write or stop can never be clobbered by a stale document copy.

## Reconciliation on startup

Before any strategy is started, `LiveOrderReconciliationService` settles the recorded orders and
the open position against the exchange, so a crash between an order submission and its response is
repaired from the exchange's order history at the next boot. It returns the ids of the strategies
it could not reconcile; `LiveStrategyStarter` passes those to `LiveStrategyService.startEnabled`,
which holds them back rather than let them trade on stale state.

- **Attribution by client order ID**: reconciliation only ever touches this strategy's own rows — a
  `PENDING` row, and a `FAILED` row that still carries a `clientOrderId`. Each is looked up in the
  history by its client order ID, and the restore recovery below re-derives IDs from this
  strategy's own sequence namespace — so nothing is adopted that a row or a re-derived ID does not
  claim. Two strategies (or the legacy TradingView path) trading one account and market therefore
  cannot contaminate each other's orders or positions.
- **Bounded to the strategy's lifetime**: the history is read with
  `GET /orders?market={market}&start={strategy.createdTime epoch millis}&limit=1000` (Bitvavo's
  maximum page size), so fills that predate the strategy are never considered.
- **Resolution**: a `PENDING` row found `filled` becomes `FILLED` in place with the exchange's
  amounts, price (derived as `filledAmountQuote / filledAmount`) and fee, clearing its error. A
  `FAILED` row found `filled` is corrected the same way and its reason records that the exchange had
  accepted the order despite the recorded failure. A `PENDING` row the exchange does not know is
  marked `FAILED` ("not found in the exchange's order history within the strategy's lifetime;
  treated as never accepted") — but only when the page is known to be complete: a non-null page
  shorter than the limit with no client-order-ID entry skipped as incomplete. When the page is not
  complete (the limit was reached, the response was null, or an entry with a client order ID could
  not be read) such a row is left `PENDING` and retried at the next boot, since "not in this page"
  is not "never accepted". A `FAILED` row the exchange does not know is left as it is either way.
- **Filled only**: only `filled` entries are acted on. An order still `open`, `partiallyFilled`,
  `canceled` or `expired` is logged and left alone (v1 does not reconcile partial or abandoned
  orders). An entry without a client order ID (the legacy webhook path, the exchange UI) cannot be
  the row being searched and is skipped at debug without affecting completeness; an entry that has a
  client order ID but is missing its order ID, creation time or side is warned and marks the page
  incomplete.
- **Upgrade note**: a real `FAILED` order recorded before the engine sent a client order ID cannot be
  attributed to this strategy, and reconciliation has no safe heuristic for it (an earlier design
  guessed by side and time, reintroducing exactly the cross-strategy misattribution the client order
  ID removes). Such a row is skipped and warned about at boot for a manual check. Locally rejected
  orders are now recorded as `REJECTED`, so a `FAILED` row without a client order ID is exactly a
  pre-change row; this deployment has only ever run dry-run, so no real order predates the change.
- **Position sync**: the strategy's filled non-dry-run orders are walked in creation order — a buy
  opens, a sell closes — and the stored open position is brought in line: a position that should be
  closed is closed in place with the last sell's price, time and realised profit, and a position
  that should be open is created from the last buy. When they already agree, nothing is written.
- **Idempotent**: once a row is settled it is no longer `PENDING` or `FAILED`, so a second run
  writes nothing.

### Restoring from a backup

A restore rewinds the recorded state to the dump while the exchange keeps trading on; the next boot
brings the two back together:

- Rows that existed at the dump are settled by client order ID as usual (a `PENDING` that filled, a
  `FAILED` the exchange accepted).
- Orders placed after the dump have no restored row — the recovery re-derives their IDs from the
  strategy's sequence counter (which the dump itself carries), recreates the rows from the
  exchange's figures, and lets the position sync close or reopen the position accordingly.
- What it cannot see: orders pushed beyond the 1000-order history page, and anything not placed by
  the engine (manual trades, the legacy webhook) — those stay the operator's business. After a
  restore, check the recovery log lines and the exchange's balance view; decrypting restored bot
  configurations needs the `ENCRYPTION_MASTER_KEY` from Bitwarden.

## API

| Endpoint | Purpose |
| --- | --- |
| `POST /api/live-strategies` | Create a strategy (market and interval required; dry-run defaults to true) |
| `GET /api/live-strategies` | List strategies |
| `GET /api/live-strategies/{id}` | Strategy plus `running` and `connected` |
| `POST /api/live-strategies/{id}/start` | Seed the history, subscribe, evaluate closed candles |
| `POST /api/live-strategies/{id}/stop` | Disconnect and clear `enabled` |
| `GET /api/live-strategies/{id}/orders` | The strategy's orders, newest first |

## Configuration

```yaml
strategy:
  live:
    websocket-url: wss://ws.bitvavo.com/v2/
    keepalive-seconds: 30
    reconnect-delay-millis: 5000
```

## Out of scope

- The TradingView webhook path still exists as the legacy fallback: PLAN.md's Phase 6 records the
  decision to keep it until the engine has traded live, and to revisit its removal then.
- Open-order reconciliation after a crash between an order and its response: the engine pre-writes
  the order with a client order ID, and the exchange's order history stays the source of truth (see
  "Reconciliation on startup"). Startup reconciliation settles only the strategy's own rows against
  `filled` orders; an order that is still open, partially filled, canceled or expired at boot is
  logged and left alone.
- Partial fills are recorded as the order response reports them (`filledAmount`,
  `filledAmountQuote`, `feePaid`), and the position uses those figures.
