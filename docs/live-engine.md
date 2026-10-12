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
  strategy's history for its market and interval — the warm-up plus a 1,000-candle convergence
  margin, seeded from the store — with the same rules, parameters and sizing as a backtest. The
  MACD/PSAR recursions converge long before that margin, so the decision at a bar matches what a
  whole-history evaluation produces there, without re-reading or re-computing a history that grows
  for the life of the process. The replay gate (`LiveSignalReplayTest`) asserts the exact equality
  that claim rests on, bar by bar, for the signals and for the MACD/SAR values.
- Signals inside the warm-up (`warmUpCandles`, default 200, as in a backtest) are ignored.
- **No stale replay**: only candles that close while the strategy runs are acted on. The seed
  itself is never traded, so downtime is not replayed; a candle that closes during a brief stream
  outage is still evaluated when the stream reconnects, and a boundary whose candle cannot be
  resolved is recorded as a `SKIPPED` row in the strategy's orders, its signal never replayed.
- **Fills**: a signal on a closed candle is filled at the **next candle's open** — the decision is
  carried one boundary forward, the same pending-fill shape `BacktestEngine` uses. Live orders hit
  the exchange at that moment; dry-run fills record that open. A decision whose boundary is lost
  (the strategy was stopped, or the candle never appeared) is dropped rather than filled late.
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
- `live_orders` — every intended or executed order: `DRY_RUN`, `FILLED` or `FAILED`, with the
  signal's candle open time, the rule set that fired, amounts, the fill or estimated price, the fee
  and the exchange order ID for live fills. A boundary whose candle could not be resolved is
  recorded as `SKIPPED`, so a missed signal window is visible through the API.
- `live_positions` — the position, closed in place (`OPEN` → `CLOSED`) with the realised profit
  after fees.

On boot, every strategy with `enabled: true` is started again (`LiveStrategyStarter`). Lifecycle
transitions are serialized: two concurrent starts can never create two runners for one strategy,
and the enabled flag is written with a targeted field update, so a concurrent last-evaluated write
or stop can never be clobbered by a stale document copy.

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

- The TradingView webhook path still exists; Phase 6 retires it.
- Open-order reconciliation after a crash between an order and its record: the engine records what
  it sent, and the exchange's order history stays the source of truth.
- Partial fills are recorded as the order response reports them (`filledAmount`,
  `filledAmountQuote`, `feePaid`), and the position uses those figures.
