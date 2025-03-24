package nl.jimkaplan.autotrader.tradingview.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request model for TradingView webhook alerts.
 * Contains the data sent by TradingView when an alert is triggered.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingViewAlertRequest {
    /**
     * Alphanumeric identifier for the bot.
     */
    private String botId;
    
    /**
     * Symbol of the traded asset (e.g., "BTCEUR").
     * Must match the bot's configured trading pair.
     */
    private String ticker;
    
    /**
     * Type of order to place (e.g., "buy" or "sell").
     */
    private String action;
    
    /**
     * UTC timestamp in format yyyy-MM-ddTHH:mm:ssZ.
     */
    private String timestamp;
}