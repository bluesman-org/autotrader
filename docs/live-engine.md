# Live engine

The live engine trades the same rules a backtest optimises, on the same candles, from Bitvavo's
WebSocket feed. It is **dry-run by default**: orders are recorded, never sent, until a strategy is
created with `dryRun: false`, once the system-wide Bitvavo API key is stored (see "The Bitvavo key").

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

- The decision bar is the candle that just closed. The engine evaluates an **anchored series** for
  its market and interval: on the first start it fixes the series start at the strategy's start
  minus the seed window (warm-up plus the MACD slow and signal periods plus a 1,000-candle margin),
  persists it as `seriesStartTime`, and from then on only appends closed candles — never truncating.
  The anchor matters because the Parabolic SAR's flip timing is start-sensitive (the rehearsal of
  2026-10-13 showed two near-identical backtests exiting on different candles): an append-only,
  fixed-start series makes restarts replay the identical decisions, and a backtest started at the
  same anchor with `warmUp` set to the window length reproduces them bar for bar on every bar the
  engine evaluated. It does not reproduce a gap: a backtest acts on every bar after its warm-up,
  while the engine never replays the seed or downtime (a boundary without a candle becomes a
  `SKIPPED` row), so after a gap the backtest can hold a position the engine never opened. If the
  store cannot reach back to the anchor (old data unavailable, e.g. right after a restore), the
  series starts later and a warning says so. The replay gate (`LiveSignalReplayTest`) still pins
  the evaluator: one case asserts candle-by-candle (prefix) equality of the signals and the
  MACD/SAR values, another that a front-truncated series matches the whole series for a benign
  fixture, and a third quantifies the same truncation on the frozen real fixture — a different start
  changes decisions there, which is the evidence this anchor exists for.
- The series is append-only, so it grows with uptime — one candle per interval, about 8.8k a year
  on `1h` and 525k on `1m` — and each decision re-evaluates it end to end. On long intervals that
  is invisible; on `1m` a strategy that runs for months should be watched (each seed logs the
  series length).
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
  reuse. The persisted sequence is advanced (a monotonic update — it can never move backwards)
  before the pre-write, so a crash can skip a number but never let a later order reuse one.
  The same row is then updated in place — to `FILLED` with the exchange's amounts, price and fee when
  the response's status is `filled`, or to `FAILED` with the error or a terminal non-fill (`canceled*`,
  `expired`, `rejected`). A status that can still fill (`new`, `partiallyFilled`, `awaitingTrigger`,
  or one Bitvavo adds later) leaves the row `PENDING` and the caller does not move a position. A
  terminal partial fill is warned and noted in the error because v1 cannot book it. While a real
  order is marked awaiting reconciliation — a pre-write, or a timeout that may have hidden an
  accepted order — `decide` stands down, so a slow or ambiguous fill cannot trigger a second real
  order; the stand-down is logged at warn once.
  An order rejected before it is sent (no API key, insufficient balance, below the market minimum,
  an exchange error before the pre-write) is recorded as `REJECTED` with no client order ID;
  dry-run rows carry none either.
- One position at a time: entry signals are ignored while a position is open or a real order is
  awaiting reconciliation, exit signals while flat.
- **Sizing**: a fixed EUR amount per buy (`amountPerBuy`, default 100, minimum 5 EUR), rounded down
  to the market's notional decimals for live orders; sells round the position quantity down to the
  market's quantity decimals. Only EUR-quoted markets are accepted, since the sizing, the balance
  check and the recorded amounts are all EUR. Simulated fees use the taker rate the backtester
  defaults to (0.20%); live fills record the exchange's `feePaid`.
- A rejected or failed order (no Bitvavo API key configured, insufficient balance, below the market
  minimum, an exchange error) is recorded with its reason, never thrown, so a running strategy
  survives it. Skipped buys stay distinguishable from plain no-signal bars through these records.

## State

- `live_strategies` — the name, configuration, `archived`, `dryRun`, `enabled`, and
  `lastEvaluatedOpenTime`: the open time of the last evaluated candle. It is persisted **before** the order for that candle is
  placed, so a crash costs a trade instead of duplicating one, and evaluation is idempotent across
  restarts.
- `live_orders` — every intended or executed order: `PENDING` (written before a real order is sent,
  and kept when the response can still fill so the next boot can settle it), `DRY_RUN`,
  `REJECTED` (a local, pre-flight rejection that never reached the exchange), `FILLED` or `FAILED`,
  with the signal's candle open time, the rule set that fired, amounts, the fill or estimated price,
  the fee, the exchange order ID for live fills and the client order ID the pre-write sent. A
  boundary whose candle could not be resolved is recorded as `SKIPPED`, so a missed signal window
  is visible through the API.
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

- **Attribution by client order ID**: reconciliation only ever touches this strategy's own rows —
  a row marked awaiting reconciliation (a pre-write or an ambiguous failure), plus a `PENDING` row
  from before the marker. Each is looked up in the history by its client order ID, and the restore
  recovery below re-derives IDs from this strategy's own sequence namespace — so nothing is adopted
  that a row or a re-derived ID does not claim. Two strategies (or the legacy TradingView path)
  trading one account and market therefore cannot contaminate each other's orders or positions.
- **Bounded to the strategy's lifetime**: the history is read with
  `GET /orders?market={market}&start={strategy.createdTime epoch millis}&limit=1000` (Bitvavo's
  maximum page size), so fills that predate the strategy are never considered.
- **Resolution**: a `PENDING` row found `filled` becomes `FILLED` in place with the exchange's
  amounts, price (derived as `filledAmountQuote / filledAmount`) and fee, clearing its error. A
  `FAILED` row found `filled` is corrected the same way and its reason records that the exchange had
  accepted the order despite the recorded failure. A row the exchange does not know is marked
  `FAILED` ("not found in the exchange's order history within the strategy's lifetime; treated as
  never accepted") — including an ambiguous `FAILED` row — but only when the page is known to be
  complete: a non-null page shorter than the limit with no client-order-ID entry skipped as
  incomplete. When the page is not complete (the limit was reached, the response was null, or an
  entry with a client order ID could not be read) the row is left awaiting reconciliation and
  retried at the next boot, since "not in this page" is not "never accepted".
- **Filled or terminal**: a `filled` entry is booked; a terminal non-fill (`canceled*`, `expired`,
  `rejected`) settles the row as `FAILED` with the exchange status in its error and a note when a
  partial fill cannot be booked (v1 does not book partial fills); an order that can still fill is
  logged and left alone. In the restore recovery below, a matched order that can still fill is
  recorded as `PENDING` so it settles once it fills, and a terminal match is skipped with a
  warning. An entry without a client order ID (the legacy webhook path, the exchange UI) cannot be
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
  strategy's sequence counter (which the dump itself carries) and recreates the rows from the
  exchange's figures: a match that can still fill is recorded as `PENDING`, so the normal
  resolution settles it once it fills, and a terminal one (`canceled`/`expired`) is skipped with a
  warning. The position sync closes or reopens the position accordingly.
- What it cannot see: orders pushed beyond the 1000-order history page, and anything not placed by
  the engine (manual trades, the legacy webhook) — those stay the operator's business. After a
  restore, check the recovery log lines and the exchange's balance view; decrypting restored bot
  configurations needs the `ENCRYPTION_MASTER_KEY` from Bitwarden.

## API

| Endpoint | Purpose |
| --- | --- |
| `POST /api/live-strategies` | Create a strategy (name, market and interval required; dry-run defaults to true) |
| `GET /api/live-strategies` | List active strategies; `?includeArchived=true` includes archived ones |
| `GET /api/live-strategies/{id}` | Strategy plus `running` and `connected` |
| `PUT /api/live-strategies/{id}` | Update a stopped strategy |
| `DELETE /api/live-strategies/{id}` | Delete a never-started strategy, or archive one that has run |
| `POST /api/live-strategies/{id}/start` | Seed the history, subscribe, evaluate closed candles |
| `POST /api/live-strategies/{id}/stop` | Disconnect and clear `enabled` |
| `GET /api/live-strategies/{id}/orders` | The strategy's orders, newest first |
| `GET /ui/live` | The live page: one card per strategy, refreshed in place every 10 seconds |
| `GET /ui/live/new` | The create form, optionally prefilled from a copy or a ranked backtest entry |
| `GET /ui/live/{id}/edit` | The edit form; the pair and interval stay fixed after the first start |
Every `/ui/**` and `/api/**` endpoint below needs a signed-in account; the TradingView webhook is
the exception, because it authenticates itself with its own webhook key (see `docs/security.md`).

| `GET /ui/history` | The account history page's engine-trades tab: the closed round trips with filters (strategy, market, type, result, time), sorting, period totals and realised P&L by day |
| `GET /ui/history?tab=ledger` | The account history page's Bitvavo-ledger tab: the account's orders with their fills, average-cost P&L, the raw transactions and the current prices |
| `GET /api/bitvavo-credentials` | The system-wide Bitvavo API key, masked: whether one is configured, a hint that identifies it and the operator id |
| `PUT /api/bitvavo-credentials` | Store or replace that key (the secret is write-only) |
| `GET /ui/settings` | The settings page: the key's state, the trading pairs, the mail settings and the password |

## Managing strategies

The live page's **New strategy** form creates a strategy without starting it. A name is required;
the startup initializer names documents created before the field existed from their pair, interval
and creation minute (e.g. `BTC-EUR 1h · 12 Oct 21:04`).

- **Pair and interval:** editable until the strategy's first start, then fixed. The candle series
  anchor belongs to that pair and timeframe, so changing either later would mix two histories under
  one record. Use **Duplicate** to try the same rules on another pair or timeframe.
- **Editing:** only a stopped strategy can be edited; the card hides the link while it runs and the
  service rejects the request. Amount, warm-up, indicator parameters and the rule sets stay
  editable. `dryRun` is chosen at creation and never switched.
- **Going real:** create a copy from a dry-run strategy with **Create real copy**. The form pre-fills
  the same rules with dry-run off and the name suffixed `-REAL`; the new strategy starts its own
  anchor, so its live record is separate from the trial's.
- **Delete or archive:** a strategy that never started is deleted. One that has run is archived:
  it leaves the live page and the forms, but its orders and positions stay, and the history tab
  keeps showing it (labelled `(archived)` in the strategy filter). An open position blocks
  archiving — close it first — so a real position can never be hidden behind an archived strategy.
- **From a backtest:** each ranked row on a stored run has a **Use live** link that opens the create
  form with the market, interval, parameters, rules and amount filled in. It saves dry-run unless
  the form's dry-run checkbox is cleared.

## The Bitvavo key

The engine's real orders and the account ledgers sign with one system-wide Bitvavo API key, stored
encrypted in `bitvavo_credentials` and managed on `/ui/settings` (or through the API above). It is
deliberately not tied to a bot configuration: the bots belong to the legacy TradingView path, which
keeps its own per-bot keys. An installation that only has the key inside a bot configuration copies
it once into the system-wide record on startup — Bitvavo shows a secret only when it is created, so
re-entering it would mean creating a new key.

## Trading pairs

The strategy forms and the account ledger do not offer Bitvavo's whole listing. A pair enters the
catalogue (`markets`) because the account traded it: startup seeds an empty catalogue from the
account's own buy/sell history, and **Refresh from Bitvavo** on the settings page repeats that read
after new trades. A pair can also be added by hand for an asset that arrived another way, such as a
deposit, which has no buy behind it. Only EUR-quoted markets are stored: the engine, the backtester
and the ledger all size and display in euro.

Discovery reads `GET /account/history` with the system-wide key, from 2020 to now and filtered to
`buy` and `sell`. The date window matters — without it the exchange returns only a recent slice of
the history — and the read is a handful of calls rather than one per market on the exchange. A pair
is disabled rather than deleted: a refresh would bring a traded pair back, and a running strategy
may still name it. A discovery leaves the enabled flag alone, so a pair an operator hid stays
hidden. Disabled pairs stay in the settings table with their history; a live strategy that trades
one keeps it visible in the forms anyway.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/markets` | Every pair in the catalogue, enabled first |
| `POST /api/markets` | Add a pair by hand (validated against Bitvavo's public market list) |
| `POST /api/markets/{market}/enable` | Offer the pair again |
| `POST /api/markets/{market}/disable` | Hide the pair without forgetting its history |
| `POST /api/markets/discover` | Read the account history and add or refresh its pairs |

## Configuration

```yaml
strategy:
  live:
    websocket-url: wss://ws.bitvavo.com/v2/
    keepalive-seconds: 30
    reconnect-delay-millis: 5000
autotrader:
  ui:
    time-zone:      # optional; e.g. Europe/Amsterdam — the machine's zone when unset
```

The pages show times in `autotrader.ui.time-zone` — the machine's zone when unset — and label them
with the zone's short name (e.g. `14:48 CEST`), so a page can never be mistaken for UTC. History
rows, the daily breakdown and a custom range's label always carry the year, because a range can span
several; the live page's recent times stay compact (the clock for today, the date and minute
otherwise).

The history page's ledger tab reads the real Bitvavo account, not the engine's own records, with
the system-wide Bitvavo API key — the same key the engine trades with (see "The Bitvavo key").
The market picker offers **All pairs** plus the enabled trading pairs (see "Trading pairs") plus
every market a live strategy trades; with no choice it opens on the newest live strategy's market,
then the most recently traded catalogue pair, then BTC-EUR. Only EUR-quoted markets are accepted.
The all-pairs view merges `GET /orders` for each catalogue market — the exchange requires a market
on that read — and sums the rows' realised results, fees and balance-after columns across them; its
summary's Unrealised values every held pair at its own bid, and its Balance is the account's euro
cash. The orders table's columns sort on their underlying values (the links keep the filters and
paging), and the all-pairs view adds a Totals by pair table. The raw view filters on the exchange's
transaction types (`deposit`, `withdrawal`, `staking`, …) and adds a Totals by type table, so
staking income per coin and external transfers read as totals; its rows show the transfer address
when Bitvavo reports one. The orders table is the spot account's: the Exchange API this page reads
exposes no margin orders or margin trades, so those are not shown here (Bitvavo's app has its own
margin ledger). The raw view lists what
`GET /account/history` returns, which can include types the published list does not name — a margin
collateral deposit, for example — and its Type filter offers those types too. Without a key the page says so instead of showing data. The orders come from `GET /orders` (with
their fills and `feePaid`), the P&L walk and the raw transactions from `GET /account/history`, the
balance from `GET /balance` and the prices from the public order book. A sell shows what it realised
at the average cost of the coins it sold — the walk covers the market's whole buy/sell history, so a
sell inside the period knows what its inventory cost — and a buy shows no result of its own: the
sell that closes it carries it. The summary's unrealised figure is the open position, the coins
still held at the current price against what they cost, and it shows only when the range ends today,
because a range that ends earlier cannot value a past holding at today's price. The walk ignores
deposits, so coins that arrived without a buy have no cost to compare against.

## Out of scope

- The TradingView webhook path still exists as the legacy fallback: PLAN.md's Phase 6 records the
  decision to keep it until the engine has traded live, and to revisit its removal then. It is
  documented in `docs/tradingview.md`.
- Open-order reconciliation after a crash between an order and its response: the engine pre-writes
  the order with a client order ID, and the exchange's order history stays the source of truth (see
  "Reconciliation on startup"). Startup reconciliation settles only the strategy's own rows against
  `filled` orders; an order that is still open, partially filled, canceled or expired at boot is
  left alone — except a lost match the recovery recorded as `PENDING`, which settles when it fills
  (see "Restoring from a backup").
- Partial fills are recorded as the order response reports them (`filledAmount`,
  `filledAmountQuote`, `feePaid`), and the position uses those figures.
