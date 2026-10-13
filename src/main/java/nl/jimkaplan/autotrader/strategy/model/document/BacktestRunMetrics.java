package nl.jimkaplan.autotrader.strategy.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The persisted form of a backtest metric set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRunMetrics {

    private double finalEquity;
    private double totalReturnPercent;
    private double maxDrawdownPercent;
    private int tradeCount;
    private double winRatePercent;

    /**
     * Gross profit over gross loss; may be infinite when there are profits and no losses.
     */
    private double profitFactor;

    /**
     * Entry signals that could not be filled; null in runs stored before the field existed.
     */
    private Integer skippedSignals;
}
