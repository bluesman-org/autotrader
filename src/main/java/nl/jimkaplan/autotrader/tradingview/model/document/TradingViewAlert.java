package nl.jimkaplan.autotrader.tradingview.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Document class for storing TradingView alerts in MongoDB.
 * Maps to the 'tradingview_alerts' collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "tradingview_alerts")
public class TradingViewAlert extends BaseDocument {

    @Field("botId")
    private String botId;

    @Field("ticker")
    private String ticker;

    @Field("action")
    private String action;

    @Field("timestamp")
    private Instant timestamp;
}