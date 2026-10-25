package nl.jimkaplan.autotrader.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public Web Push assets are the only push paths that skip the sign-in; the subscription API
 * stays behind the session, which the controller slice pins separately.
 */
class SecurityConfigOpenPathsTest {

    @Test
    void onlyThePushStaticAssetsAreOpen() {
        assertThat(SecurityConfig.OPEN_PATHS)
                .contains("/service-worker.js", "/manifest.json", "/icon.svg")
                .doesNotContain("/api/push/public-key", "/api/push/subscriptions", "/api/push/test");
    }
}
