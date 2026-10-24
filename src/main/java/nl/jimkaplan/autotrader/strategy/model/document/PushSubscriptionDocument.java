package nl.jimkaplan.autotrader.strategy.model.document;

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
 * One browser's Web Push subscription. The endpoint is a capability URL: anyone holding it can
 * send a notification to that browser, so it is kept server-side only. The p256dh and auth values
 * encrypt the payload to the browser.
 *
 * <p>Maps to the {@code push_subscriptions} collection.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "push_subscriptions")
public class PushSubscriptionDocument extends BaseDocument {

    /**
     * The push service URL the browser returned.
     */
    @Field("endpoint")
    private String endpoint;

    /**
     * The browser's public key for the encrypted payload.
     */
    @Field("p256dh")
    private String p256dh;

    /**
     * The browser's auth secret for the encrypted payload.
     */
    @Field("auth")
    private String auth;

    @Field("created_time")
    private Instant createdTime;

    @Field("last_success_time")
    private Instant lastSuccessTime;

    @Field("last_failure_time")
    private Instant lastFailureTime;

    /**
     * The browser that registered it, for the settings page and troubleshooting.
     */
    @Field("user_agent")
    private String userAgent;
}
