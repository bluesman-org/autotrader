package nl.jimkaplan.autotrader.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * The system-wide Bitvavo API key: one key for the account, used by the live engine (real orders,
 * reconciliation, balance) and by the account-history ledger. It is deliberately not bound to a bot
 * configuration — the bots belong to the legacy TradingView path and keep their own keys.
 *
 * <p>The key and secret are stored encrypted (AES-256-GCM under the master key from
 * {@code ENCRYPTION_MASTER_KEY}); only the plaintext, filled into the transient fields when the
 * service hands the credentials out, ever signs a request. The document is a singleton: the service
 * saves it under {@link #ID}, so replacing the key replaces the record.</p>
 */
@Data
@ToString(exclude = {"apiKey", "apiSecret"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "bitvavo_credentials")
public class BitvavoCredentials {

    /**
     * The singleton document's id.
     */
    public static final String ID = "default";

    @Id
    private String id;

    @Field("encrypted_api_key")
    private String encryptedApiKey;

    @Field("encrypted_api_secret")
    private String encryptedApiSecret;

    /**
     * The operator id Bitvavo expects on order submissions.
     */
    @Field("operator_id")
    private Long operatorId;

    @Field("key_version")
    private Integer keyVersion;

    /**
     * When the key was stored or replaced; set explicitly because the application enables no Mongo
     * auditing.
     */
    @Field("updated_time")
    private Instant updatedTime;

    /**
     * Transient fields not stored in the database, filled by the service.
     */
    private transient String apiKey;
    private transient String apiSecret;
}
