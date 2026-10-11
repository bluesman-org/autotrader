# Rule specifications

This document specifies how strategies are defined and evaluated. The indicators themselves are
specified in `docs/indicators.md`; the rules here are built on their outputs.

## Strategy definition

A strategy is four things:

1. **MACD parameters** — `fastPeriod`, `slowPeriod`, `signalPeriod` (conventional: 12/26/9)
2. **Parabolic SAR parameters** — `acceleration`, `maximum` (conventional: 0.02/0.2)
3. **Entry rules** — a set of conditions, combined with one operator
4. **Exit rules** — an independent set of conditions, combined with one operator

The two rule sets are defined separately on purpose: a strategy may enter on one indicator's
signal and exit on the other's (the requirement this engine was built for). The parameter
records validate eagerly, so a configuration taken from the UI or stored in the database fails
at construction rather than at calculation.

The parameter records are the shared parameter model: the backtester sweeps them, the UI edits
them and the live engine reads them.

## Conditions

Conditions are evaluated on **closed candles only**, on the bar's close and the indicator values
at that bar.

| Condition | Holds when |
| --- | --- |
| `MACD_BULLISH_CROSSOVER` | MACD line was at or below the signal line on the previous bar and is above it on this bar |
| `MACD_BEARISH_CROSSOVER` | MACD line was at or above the signal line on the previous bar and is below it on this bar |
| `MACD_ABOVE_SIGNAL` | MACD line > signal line (strictly) |
| `MACD_BELOW_SIGNAL` | MACD line < signal line (strictly) |
| `HISTOGRAM_POSITIVE` | Histogram (MACD − signal) > 0 |
| `HISTOGRAM_NEGATIVE` | Histogram < 0 |
| `PRICE_ABOVE_SAR` | Close > Sar (strictly) |
| `PRICE_BELOW_SAR` | Close < Sar (strictly) |
| `SAR_BULLISH_CROSS` | Close was at or below the SAR on the previous bar and is above it on this bar |
| `SAR_BEARISH_CROSS` | Close was at or above the SAR on the previous bar and is below it on this bar |

**Warm-up and unavailable inputs.** Any condition whose inputs are not available — the
indicator is still in its warm-up on this or the previous bar, or there is no previous bar — is
`false`. It therefore never fires, and a strategy simply produces no signals until its indicators
are defined.

**Crossovers.** The previous bar is included with "at or below"/"at or above" so a cross that
happens to start from an exact touch still counts; equality on the current bar does not count as
crossing. A crossover can only fire from the second bar with a defined value onward.

## Combination

A rule group combines its conditions with one operator:

- `ALL` — every condition must hold (AND); a group with several conditions fires only when they
  all do.
- `ANY` — at least one condition must hold (OR).

Groups must contain at least one condition; an empty set is rejected at construction rather than
being silently treated as "never fires" or "always fires".

## Signals and position logic

Evaluation produces two boolean series aligned with the candles: `entry[i]` and `exit[i]`. They
are **raw signals**: they say the rules fired on bar `i`, nothing more. A repeated condition
(e.g. `PRICE_ABOVE_SAR`) is true on every bar it holds, so it fires repeatedly; the position
logic — entering once, holding a single position, filling at the next candle's open, applying
fees and recording skipped signals — belongs to the backtester and the live engine, which consume
these series. Both run the same rules, so a strategy is validated on exactly the behaviour it
will execute.

## Worked example

Closes `10, 9, 8, 7, 6, 5, 6, 7, 8, 9, 10, 11, 12, 13` with MACD(3, 5, 2) — the reversal used in
`StrategyEvaluatorTest`:

- fast EMA (k = 0.5): seeded with `SMA(10, 9, 8) = 9` at index 2, then
  `9, 8, 7, 6, 6, 6.5, 7.25, 8.125, 9.0625, 10.03125, 11.015625, 12.0078125`
- slow EMA (k = 1/3): seeded with `SMA(10, 9, 8, 7, 6) = 8` at index 4, then
  `8, 7, 6.666667, 6.777778, 7.185185, 7.790123, 8.526749, 9.351167, 10.234111, 11.155741`
- the fast EMA is seeded on the tail of the slow window (equal to its sequential value here), so
  the MACD line starts at index 4: `-1, -1, -0.666667, -0.277778, 0.064815, 0.334877, 0.535751, …`
- the signal line (period 2) is seeded with the average of the first two MACD values
  (`-1` at index 5) and updates with k = 2/3: `-1, -0.777778, -0.444444, -0.104938, 0.188272, …`

On index 5 the line and the signal both sit at `-1`; on index 6 the line is above the signal
(`-0.666667` vs `-0.777778`). Because the previous bar counts as "at or below" (the touch), an
entry rule set of `ALL [MACD_BULLISH_CROSSOVER]` fires exactly once, at index 6. For comparison,
a set that used only `HISTOGRAM_POSITIVE` would hold from index 6 onward — the histogram turns
positive exactly when the line crosses — so the crossover is the narrower trigger, and that is
exactly the behaviour the entry/exit sets let you choose between.
