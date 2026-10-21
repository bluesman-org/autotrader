package nl.jimkaplan.autotrader.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * The SMTP server the recovery mails go out through, configured on the settings page or through the
 * API. A singleton, like the Bitvavo key: one account, one server.
 *
 * <p>The password is stored encrypted (AES-256-GCM under {@code ENCRYPTION_MASTER_KEY}) and is
 * write-only — no page or response ever carries it back.</p>
 */
@Data
@ToString(exclude = "password")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mail_settings")
public class MailSettings {

    /**
     * The singleton document's id.
     */
    public static final String ID = "default";

    @Id
    private String id;

    @Field("host")
    private String host;

    @Field("port")
    private Integer port;

    @Field("username")
    private String username;

    @Field("encrypted_password")
    private String encryptedPassword;

    @Field("from_address")
    private String fromAddress;

    /**
     * Whether STARTTLS is used; the default port is 587 with it, 25 without.
     */
    @Field("start_tls")
    private boolean startTls;

    @Field("updated_time")
    private Instant updatedTime;

    /**
     * Transient field not stored in the database, filled by the service.
     */
    private transient String password;
}
