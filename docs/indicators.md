# Indicator specifications

This document is the written specification the indicator implementations and their tests are
built against. It records the exact conventions used, including the ones a bare textbook
definition leaves open (seeds, warm-up, clamping), so the behaviour is reproducible and can be
compared with the reference implementation.

Both indicators output arrays **aligned with the input bars**. Entries before the first valid
bar are `NaN`; an input too short for a single value yields an all-`NaN` result. All arithmetic
mirrors the reference implementation (TA-Lib) operation for operation, including its use of
fused multiply-add, so outputs match it **bit for bit** — pinned by the frozen fixtures in
`src/test/resources/indicators/` (see "Fixture provenance" below).

## MACD

The MACD line is `EMA(fast) - EMA(slow)` over closing prices; the signal line is an EMA of the
MACD line; the histogram is their difference.

### EMA bootstrap

An EMA with period `p` uses `k = 2 / (p + 1)` and is seeded with the **simple average of the
first `p` inputs of its own window**, accumulated in input order from 0.0. Each subsequent
value is `fma(input - previous, k, previous)`.

### Single-pass computation (the exact order matters)

With `n` inputs, periods `fast`, `slow` (matching the reference: each price-EMA window is
placed so it ends on the slow EMA's first valid bar) and `signal`:

1. `prevSlow = average(closes[0 .. slow-1])`
2. `prevFast = average(closes[slow-fast .. slow-1])`
3. `macdValue = prevFast - prevSlow` (the MACD line at index `slow-1`)
4. The signal EMA is seeded with the **simple average of the first `signal` MACD values**:
   accumulate `macdValue`, then, for each of the next `signal - 1` inputs, advance both price
   EMAs and accumulate the new MACD value.
5. The first output is written at index `slow + signal - 2`. From the next input on, each bar
   advances both EMAs, computes the MACD value, and updates the signal
   (`fma(macdValue - prevSignal, signalK, prevSignal)`; for `signal == 1` the signal *is* the
   MACD line).
6. `histogram = macdValue - prevSignal`.

Requires `1 <= fast <= slow` and `signal >= 1`; the shortest input producing a value has
`slow + signal - 1` bars.

## Parabolic SAR

Wilder's stop-and-reverse, computed from bar highs and lows. Parameters: acceleration factor
(default 0.02) and maximum (default 0.2). If the acceleration exceeds the maximum it is capped
before use, and that capped value is also the amount by which the factor grows.

### Bootstrap

- The initial **direction** is derived from the directional movement between the first two
  bars: the downward move is `low[0] - low[1]` (counted only when positive and not exceeded by
  the upward move `high[1] - high[0]`); a non-zero downward move starts the series **short**,
  otherwise **long** (ties are long).
- Initial extreme point and SAR: long uses `ep = high[1]`, `sar = low[0]`; short uses
  `ep = low[1]`, `sar = high[0]`.
- The first output is written at index 1.

### Per-bar rules

For each bar `t` (with `prev` = bar `t-1`, `new` = bar `t`):

- **Long**: if `low[t] <= sar` the direction reverses to short — `sar = ep` clamped up to at
  least `max(prevHigh, newHigh)`, that override is the bar's output, then the factor resets,
  `ep = low[t]`, the next `sar` starts from `fma(af, ep - sar, sar)` and is clamped the same
  way. Otherwise the bar's output is the current `sar`; if `high[t] > ep` then `ep = high[t]`
  and the factor grows (capped at the maximum); the next `sar` is `fma(af, ep - sar, sar)`,
  clamped down to at most `min(prevLow, newLow)`.
- **Short**: mirror image — reversal when `high[t] >= sar` with the override clamped down to at
  most `min(prevLow, newLow)` and subsequent clamps upward to `max(prevHigh, newHigh)`; a new
  extreme lowers `ep` and grows the factor the same way.

The clamps are what keep the SAR from entering the previous two bars' range; the reversal
override sits outside the current bar's range.

## Fixture provenance

`btc_eur_1h_ohlc.csv` is the first 500 BTC-EUR 1-hour candles from the local candle store
(exported 2026-10-11). `macd_btc_eur_1h_12_26_9.csv` and `sar_btc_eur_1h.csv` contain the
reference outputs for those bars; `macd_linear_3_5_2.csv`, `sar_flat.csv`,
`sar_oscillating.csv` and `sar_descending.csv` cover the crafted edge cases. All of them were
generated with `scripts/generate-indicator-fixtures.py`, which prints the exact toolchain it
ran with — **the frozen values pin that build** (TA-Lib 0.8.1, the build that fuses the EMA
kernel as shown above). Bit-exactness is build-sensitive: another TA-Lib version, or a build
whose compiler contracts differently, can legitimately differ in the last bit, and the tests
compare against the committed values with zero tolerance. TA-Lib is fixture provenance only
and is not a runtime or build dependency of the application.
