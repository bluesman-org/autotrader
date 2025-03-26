package nl.jimkaplan.autotrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import nl.jimkaplan.autotrader.model.document.BaseDocument;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Document class for storing orders placed on Bitvavo in response to TradingView alerts.
 * Maps to the 'orders' collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "orders")
public class Order extends BaseDocument {

    @Field("botId")
    private String botId;

    @Field("order_id")
    private String orderId;

    @Field("ticker")
    private String ticker;

    @Field("timestamp")
    private Instant timestamp;

    @Field("status")
    private String status;

    @Field("error_message")
    private String errorMessage;
}