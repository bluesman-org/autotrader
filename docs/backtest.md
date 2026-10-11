# Backtest specifications

This document specifies how strategies are simulated. The indicators are specified in
`docs/indicators.md` and the rules in `docs/rules.md`; here is what happens between a signal and
a number that can be ranked.

## Simulation contract

- **Decisions on closed candles only.** Signals are read at bar `i` after that bar has closed.
- **Fills at the next candle's open.** A signal at bar `i` fills at the open of bar `i + 1`. A
  signal on the last bar is dropped — there is no bar to fill at.
- **One position at a time.** Entry signals are ignored while a position is open; exit signals
  are ignored while flat. A repeated entry signal after a skipped fill tries again on the next
  bar, exactly as the live engine would re-evaluate.
- **Fixed EUR amount per buy.** Each buy spends the configured amount, before fees. If the
  remaining cash cannot cover the amount **plus its fee**, the signal is skipped and recorded
  (`INSUFFICIENT_BALANCE`) against the signal's own bar, rather than partially filled.
- **Fees per side, in EUR.** A buy costs `amount + amount × feeRate`; a sell returns
  `gross − gross × feeRate`, where `gross = quantity × price`. This mirrors the exchange's
  charging model. The default is the account's taker rate (**0.20%** — market orders take
  liquidity); the maker rate applies only to limit orders.
- **Open positions are closed at the last close**, paying the exit fee, so every run ends flat.
- **Equity is marked at every bar's close** (cash plus the position's value at that close), and
  the drawdown is measured against the running peak of that equity curve.
- **Warm-up.** The first `warmUpCandles` bars are never traded. This is a buffer on top of the
  indicators' own warm-up.
- **Determinism.** Output is a pure function of the series, signals and settings: no clock, no
  randomness, stable ordering everywhere.

## Metrics

| Metric | Definition |
| --- | --- |
| `finalEquity` | Cash at the end, in EUR (every run ends flat) |
| `totalReturnPercent` | `(finalEquity − initialCapital) / initialCapital × 100` |
| `maxDrawdownPercent` | Largest peak-to-trough decline of the bar-close equity, in percent |
| `tradeCount` | Completed round trips |
| `winRatePercent` | Share of trades with positive net profit; `0` when there are no trades |
| `profitFactor` | Gross profit ÷ gross loss; `0` with no trades, **infinite** with profits and no losses |

## Train/validate split

A sweep optimises on the **earlier** portion of the range and verifies on the held-out **newer**
portion:

- The split point is `floor(bars × trainRatio)`; the default ratio is 0.7.
- Indicators are computed once over the whole range (the validate side is allowed to look back
  into earlier data — that is past information, not look-ahead), and both sides start from the
  same initial capital.
- The configured warm-up applies to the **train** side only: the validate side trades from its
  first bar because its indicators already carry the earlier data's warm-up. The two sides
  therefore trade under different warm-up conditions by design.
- Each combination is ranked by the configured metric on the **train** side; the validate side
  is reported next to it so a strategy that only worked in-sample is visible.
- **A combination whose train side never traded ranks last**, whatever the metric: a flat curve
  is no evidence, and it would otherwise win "best drawdown" (0%) over every strategy that
  risked capital.
- Win rate is a plain ratio, so a single lucky trade scores 100% — read it together with the
  trade count.
- Ranking is stable and deterministic: ties keep the combination's grid order.

## Sweep bounds

A sweep is the Cartesian product of the candidate entry rule sets, exit rule sets, MACD
parameters and Parabolic SAR parameters. The product must not exceed **5000 combinations**; a
larger request is rejected rather than silently truncated. The cap exists because every
combination is simulated over every bar — the bound keeps a sweep interactive.

## API

`POST /api/backtests` runs a sweep and stores the outcome. Only the market, interval and range
are required; everything else falls back to the documented defaults:

| Field | Default |
| --- | --- |
| `initialCapital` | 1000 EUR |
| `amountPerBuy` | 100 EUR |
| `feeRate` | 0.002 (the account's taker rate) |
| `warmUpCandles` | 200 |
| `trainRatio` | 0.7 |
| `rankingMetric` | `TOTAL_RETURN` |
| `macdParameters` | `[{12, 26, 9}]` |
| `sarParameters` | `[{0.02, 0.2}]` |
| `entryRules` | `ALL [MACD_BULLISH_CROSSOVER]` |
| `exitRules` | `ALL [SAR_BEARISH_CROSSOVER]` |

The run is synchronous and fetches any missing candles from Bitvavo first, so a request over a
large range takes longer; the combination cap bounds the sweep itself. Only the best 50 entries
are stored. `GET /api/backtests/{id}` reads a run back, `GET /api/backtests?limit=20` lists the
most recent runs (entries omitted from the list). A `profitFactor` of infinity is serialized as
the JSON string `"Infinity"` (Jackson's standard handling of non-finite numbers).
