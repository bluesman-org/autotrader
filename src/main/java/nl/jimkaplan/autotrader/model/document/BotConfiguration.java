package nl.jimkaplan.autotrader.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Document class for storing bot configurations including encrypted API keys and secrets.
 * Maps to the 'bot_configurations' collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "bot_configurations")
public class BotConfiguration extends BaseDocument {

    @Indexed(unique = true)
    @Field("botId")
    private String botId;

    @Field("encryptedApiKey")
    private String encryptedApiKey;

    @Field("encryptedApiSecret")
    private String encryptedApiSecret;

    @Field("trading_pair")
    private String tradingPair;

    @Field("webhookKeyHash")
    private String webhookKeyHash;

    @Field("key_version")
    private Integer keyVersion;

    @Field("active")
    @Builder.Default
    private Boolean active = true;

    // Transient fields not stored in the database
    private transient String apiKey;
    private transient String apiSecret;
}
