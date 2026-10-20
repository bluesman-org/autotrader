package nl.jimkaplan.autotrader.strategy.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Request to create or update a live strategy. On create the name, market and interval are
 * required; the rest falls back to the same documented defaults a backtest uses, with dry-run on
 * by default. On update null fields keep the stored value, and dry-run cannot be switched.
 */
@Data
public class LiveStrategyRequest {

    @Schema(description = "Operator-facing strategy name; required when creating", example = "BTC 2h breakout")
    private String name;

    @Schema(description = "Market in Bitvavo format", example = "BTC-EUR")
    private String market;

    @Schema(description = "Candlestick interval code", example = "1h")
    private String interval;

    @Schema(description = "Fixed EUR amount per buy, before fees", example = "100")
    private Double amountPerBuy;

    @Schema(description = "Leading candles the strategy never trades; defaults to 200", example = "200")
    private Integer warmUpCandles;

    @Schema(description = "Record orders without sending them; defaults to true", example = "true")
    private Boolean dryRun;

    @Schema(description = "MACD parameters; defaults to 12/26/9")
    private MacdParametersRequest macdParameters;

    @Schema(description = "Parabolic SAR parameters; defaults to 0.02/0.2")
    private SarParametersRequest sarParameters;

    @Schema(description = "Entry rule set; defaults to a MACD bullish crossover")
    private RuleSetRequest entryRules;

    @Schema(description = "Exit rule set; defaults to a bearish SAR crossover")
    private RuleSetRequest exitRules;
}
