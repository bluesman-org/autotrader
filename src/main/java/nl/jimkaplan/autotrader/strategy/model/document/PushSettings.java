package nl.jimkaplan.autotrader.strategy.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * The installation's Web Push key material. One document, generated on the first request for the
 * VAPID public key and then kept: rotating the pair invalidates every browser subscription, so it
 * is deliberately stable and backed up with the database.
 *
 * <p>Maps to the {@code push_settings} collection. The private key is stored encrypted with the
 * same master key as the exchange credentials.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "push_settings")
public class PushSettings {

    /**
     * The singleton document id.
     */
    public static final String ID = "default";

    @Id
    private String id;

    /**
     * Base64 of the PKCS#8 private key, then AES-GCM encrypted as a Base64 string.
     */
    @Field("encrypted_private_key")
    private String encryptedPrivateKey;

    /**
     * Base64 of the X.509 public key; not secret, kept so the pair can be rebuilt without the
     * private key being touched.
     */
    @Field("public_key")
    private String publicKey;

    @Field("created_time")
    private Instant createdTime;
}
