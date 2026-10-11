package nl.jimkaplan.autotrader.strategy.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import nl.jimkaplan.autotrader.strategy.backtest.RankingMetric;

import java.util.List;

/**
 * Request to run a backtest sweep. Only the market, interval and range are required; every other
 * field falls back to its documented default.
 */
@Data
public class BacktestRunRequest {

    @Schema(description = "Market in Bitvavo format", example = "BTC-EUR")
    private String market;

    @Schema(description = "Candlestick interval code", example = "1h")
    private String interval;

    @Schema(description = "Range start, epoch milliseconds", example = "1783069200000")
    private Long fromDate;

    @Schema(description = "Range end, epoch milliseconds", example = "1791705600000")
    private Long toDate;

    @Schema(description = "Starting cash in EUR", example = "1000")
    private Double initialCapital;

    @Schema(description = "Fixed EUR amount per buy, before fees", example = "100")
    private Double amountPerBuy;

    @Schema(description = "Fee per side as a fraction; defaults to the account's taker rate",
            example = "0.002")
    private Double feeRate;

    @Schema(description = "Leading candles that are never traded", example = "200")
    private Integer warmUpCandles;

    @Schema(description = "Share of the range the sweep optimises on", example = "0.7")
    private Double trainRatio;

    @Schema(description = "Metric to rank by", example = "TOTAL_RETURN")
    private RankingMetric rankingMetric;

    @Schema(description = "MACD parameter candidates; defaults to 12/26/9")
    private List<MacdParametersRequest> macdParameters;

    @Schema(description = "Parabolic SAR parameter candidates; defaults to 0.02/0.2")
    private List<SarParametersRequest> sarParameters;

    @Schema(description = "Entry rule sets to sweep; defaults to a MACD bullish crossover")
    private List<RuleSetRequest> entryRules;

    @Schema(description = "Exit rule sets to sweep; defaults to a bearish SAR crossover")
    private List<RuleSetRequest> exitRules;
}
