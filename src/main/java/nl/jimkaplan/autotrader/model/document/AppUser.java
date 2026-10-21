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
import java.util.List;

/**
 * The application's account: the one user who signs in to the UI and the API. The application is
 * deliberately single-account — it trades one Bitvavo account, so a second user would have nothing
 * of their own — and the email exists for password recovery only.
 *
 * <p>The password is stored as a BCrypt hash and the record never carries the plaintext; the
 * username and the email are stored lowercased, and the indexes that make them unique are created
 * by {@code MongoIndexInitializer} (annotation-driven index creation is off in this application).</p>
 */
@Data
@ToString(exclude = "passwordHash")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "app_users")
public class AppUser {

    /**
     * The role every account holds; the application has no finer authority than "signed in".
     */
    public static final String ROLE_USER = "USER";

    @Id
    private String id;

    /**
     * The login name, lowercased and unique.
     */
    @Field("username")
    private String username;

    /**
     * The recovery address, lowercased and unique.
     */
    @Field("email")
    private String email;

    @Field("password_hash")
    private String passwordHash;

    /**
     * What the pages call the reader; defaults to the username.
     */
    @Field("display_name")
    private String displayName;

    @Field("roles")
    private List<String> roles;

    @Field("enabled")
    private boolean enabled;

    @Field("created_time")
    private Instant createdTime;

    /**
     * When the account last signed in, for the settings page.
     */
    @Field("last_login_time")
    private Instant lastLoginTime;
}
