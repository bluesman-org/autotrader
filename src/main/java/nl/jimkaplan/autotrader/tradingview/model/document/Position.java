package nl.jimkaplan.autotrader.tradingview.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Document class for tracking open positions for each bot.
 * Maps to the 'positions' collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "positions")
public class Position extends BaseDocument {

    @Field("botId")
    private String botId;

    @Field("ticker")
    private String ticker;

    @Field("status")
    private String status;
}