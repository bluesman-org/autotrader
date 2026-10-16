package nl.jimkaplan.autotrader.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * A password-recovery request: what the mail's link proves, and nothing more.
 *
 * <p>The token itself is never stored — only its SHA-256 hash, so a copy of the database cannot be
 * turned into working links. There is one document per account (its id is the account's id): a new
 * request replaces the previous token, which is how "one live token per user" is enforced.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "password_reset_tokens")
public class PasswordResetToken {

    /**
     * The account's id, so one account holds at most one live token.
     */
    @Id
    private String id;

    @Field("token_hash")
    private String tokenHash;

    @Field("created_time")
    private Instant createdTime;

    @Field("expires_time")
    private Instant expiresTime;

    /**
     * When the token was used; a token with a used time is spent, whatever its expiry says.
     */
    @Field("used_time")
    private Instant usedTime;
}
