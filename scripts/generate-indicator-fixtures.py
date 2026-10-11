#!/usr/bin/env python3
"""Generate the frozen indicator fixtures with TA-Lib.

TA-Lib is fixture provenance only: it is never a dependency of the application or its build.
The committed fixture files are what the tests read; this script regenerates them.

Usage:
    <python-with-ta-lib>/bin/python scripts/generate-indicator-fixtures.py [fixture-dir]

The script assumes btc_eur_1h_ohlc.csv (open_time,high,low,close) already exists in the fixture
directory; it is the first 500 BTC-EUR 1-hour candles exported from the local candle store:

    db.candles.find({market: "BTC-EUR", interval: "1h"}).sort({open_time: 1}).limit(500)

Generated files:
    macd_btc_eur_1h_12_26_9.csv   close,macd,signal,histogram for those candles
    sar_btc_eur_1h.csv            high,low,sar_0.02_0.2,sar_0.05_0.5 for those candles
    macd_linear_3_5_2.csv         close,macd,signal,histogram for closes 1..40
    sar_flat.csv                  high,low,sar for a flat series (high=2, low=1)
    sar_oscillating.csv           high,low,sar for an up-then-down series
"""

import csv
import sys
from pathlib import Path

import numpy as np
import talib


def fmt(value):
    if np.isnan(value):
        return "NaN"
    return repr(float(value))


def write_csv(path, header, columns):
    with path.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        for row in zip(*columns):
            writer.writerow([fmt(cell) for cell in row])


def main():
    fixture_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "src/test/resources/indicators")

    rows = list(csv.DictReader((fixture_dir / "btc_eur_1h_ohlc.csv").open()))
    close = np.array([float(row["close"]) for row in rows])
    high = np.array([float(row["high"]) for row in rows])
    low = np.array([float(row["low"]) for row in rows])

    macd, signal, histogram = talib.MACD(close, fastperiod=12, slowperiod=26, signalperiod=9)
    write_csv(fixture_dir / "macd_btc_eur_1h_12_26_9.csv",
              ["close", "macd", "signal", "histogram"],
              [close, macd, signal, histogram])

    sar_default = talib.SAR(high, low, acceleration=0.02, maximum=0.2)
    sar_fast = talib.SAR(high, low, acceleration=0.05, maximum=0.5)
    write_csv(fixture_dir / "sar_btc_eur_1h.csv",
              ["high", "low", "sar_0.02_0.2", "sar_0.05_0.5"],
              [high, low, sar_default, sar_fast])

    linear = np.arange(1, 41, dtype=float)
    macd_linear, signal_linear, histogram_linear = talib.MACD(linear, fastperiod=3, slowperiod=5,
                                                              signalperiod=2)
    write_csv(fixture_dir / "macd_linear_3_5_2.csv",
              ["close", "macd", "signal", "histogram"],
              [linear, macd_linear, signal_linear, histogram_linear])

    flat_high = np.array([2.0, 2.0, 2.0, 2.0, 2.0])
    flat_low = np.array([1.0, 1.0, 1.0, 1.0, 1.0])
    write_csv(fixture_dir / "sar_flat.csv", ["high", "low", "sar"],
              [flat_high, flat_low, talib.SAR(flat_high, flat_low, acceleration=0.02, maximum=0.2)])

    oscillating_high = np.array([1.0, 2.0, 3.0, 4.0, 5.0, 4.0, 3.0, 2.0])
    oscillating_low = np.array([0.5, 1.5, 2.5, 3.5, 4.5, 3.5, 2.5, 1.5])
    write_csv(fixture_dir / "sar_oscillating.csv", ["high", "low", "sar"],
              [oscillating_high, oscillating_low,
               talib.SAR(oscillating_high, oscillating_low, acceleration=0.02, maximum=0.2)])

    print(f"fixtures written to {fixture_dir}")


if __name__ == "__main__":
    main()
