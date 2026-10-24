package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.config.SecurityConfig;
import nl.jimkaplan.autotrader.service.AppUserService;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.service.MailSettingsService;
import nl.jimkaplan.autotrader.service.MarketService;
import nl.jimkaplan.autotrader.service.PasswordResetService;
import nl.jimkaplan.autotrader.strategy.service.LiveAlertService;
import nl.jimkaplan.autotrader.strategy.service.PushNotificationService;
import nl.jimkaplan.autotrader.strategy.service.PushSettingsService;
import nl.jimkaplan.autotrader.strategy.service.PushSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rules the security filter chain enforces: pages ask for a sign-in, the API answers 401 and
 * takes Basic, the webhook and the sign-in pages stay open, and a protected form needs its token.
 */
@WebMvcTest(controllers = {AuthController.class, SettingsUiController.class}, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk=",
        "autotrader.ui.time-zone=UTC"
})
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserService users;
    @MockitoBean
    private PasswordResetService recovery;
    @MockitoBean
    private BitvavoCredentialsService credentialsService;
    @MockitoBean
    private MailSettingsService mailSettingsService;
    @MockitoBean
    private MarketService marketService;
    @MockitoBean
    private LiveAlertService alerts;
    @MockitoBean
    private PushSettingsService pushSettingsService;
    @MockitoBean
    private PushSubscriptionService pushSubscriptionService;
    @MockitoBean
    private PushNotificationService pushNotificationService;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void aPageSendsAnAnonymousReaderToTheSignInPage() throws Exception {
        mockMvc.perform(get("/ui/live"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void theApiAnswersUnauthorizedInsteadOfASignInPage() throws Exception {
        mockMvc.perform(get("/api/bitvavo-credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theApiTakesHttpBasicForTheSameAccount() throws Exception {
        when(users.loadUserByUsername("jim")).thenReturn(User.withUsername("jim")
                .password(encoder.encode("secret")).roles("USER").build());
        when(users.exists()).thenReturn(true);

        // No handler lives in this slice, so reaching the dispatcher (404) is what proves the
        // Basic credentials were accepted.
        mockMvc.perform(get("/api/bitvavo-credentials").with(httpBasic("jim", "secret")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theSignInPageItselfStaysOpen() throws Exception {
        when(users.exists()).thenReturn(true);

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void theSignupPageIsOpenWhileNoAccountExists() throws Exception {
        when(users.exists()).thenReturn(false);

        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk());
    }

    @Test
    void theRecoveryPagesStayOpen() throws Exception {
        mockMvc.perform(get("/forgot-password"))
                .andExpect(status().isOk());
        when(recovery.valid(null)).thenReturn(false);
        mockMvc.perform(get("/reset-password"))
                .andExpect(status().isOk());
    }

    @Test
    void thePushApiNeedsASignIn() throws Exception {
        mockMvc.perform(get("/api/push/public-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theLocalSpecificationExporterMayReadTheApiShape() throws Exception {
        // The exporter runs on this machine and fetches /v3/api-docs over the loopback interface;
        // reaching the dispatcher (404 in this slice) is what proves the rule admitted it.
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRemoteReaderOfTheApiShapeSignsIn() throws Exception {
        mockMvc.perform(get("/v3/api-docs").with(request -> {
                    request.setRemoteAddr("203.0.113.7");
                    return request;
                }))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void aForwardedHeaderDoesNotBuyTheApiShape() throws Exception {
        // The rule keys on the address the container hands over. Tomcat only rewrites that from
        // X-Forwarded-For for a trusted peer, and ignores `Forwarded` altogether — so a header alone
        // must leave a remote caller outside (this slice has no forwarded-header filter, exactly
        // like the production chain, which uses Tomcat's valve instead of Spring's filter).
        mockMvc.perform(get("/v3/api-docs")
                        .header("Forwarded", "for=127.0.0.1")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        }))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void aSignedInReaderGetsTheApiShape() throws Exception {
        // Swagger UI fetches its configuration as the reader's browser, which is not loopback; the
        // session admits it, like every other page.
        mockMvc.perform(get("/v3/api-docs").with(user("jim")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theWebhookNeedsNoSessionAndNoToken() throws Exception {
        // Reaching the dispatcher (404 in this slice) shows the path is open and past CSRF.
        mockMvc.perform(post("/webhook/tradingview")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void signingInLandsOnTheLivePage() throws Exception {
        when(users.loadUserByUsername("jim")).thenReturn(User.withUsername("jim")
                .password(encoder.encode("secret")).roles("USER").build());

        mockMvc.perform(post("/login").with(csrf())
                        .param("username", "jim")
                        .param("password", "secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/live"));

        verify(users).recordLogin("jim");
    }

    @Test
    void aWrongPasswordComesBackToTheForm() throws Exception {
        when(users.loadUserByUsername("jim"))
                .thenThrow(new UsernameNotFoundException("no account"));

        mockMvc.perform(post("/login").with(csrf())
                        .param("username", "jim")
                        .param("password", "wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void signingOutReturnsToTheSignInPage() throws Exception {
        // A browser asks for HTML, and Spring Security 6.4 redirects that kind of client; a JSON or
        // unknown client gets 204 instead, which the tests below pin down for the API's sake.
        mockMvc.perform(post("/logout").with(csrf()).with(user("jim")).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));

        mockMvc.perform(post("/logout").with(csrf()).with(user("jim")))
                .andExpect(status().isNoContent());
    }

    @Test
    void aSignedInAccountChangesItsPassword() throws Exception {
        mockMvc.perform(post("/ui/settings/password").with(csrf()).with(user("jim"))
                        .param("current", "current-password")
                        .param("password", "new-password-1")
                        .param("confirm", "new-password-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));

        verify(users).changePassword("jim", "current-password", "new-password-1", "new-password-1");
    }

    @Test
    void aProtectedFormWithoutTheTokenIsRefused() throws Exception {
        mockMvc.perform(post("/ui/settings").with(user("jim")).param("apiKey", "key"))
                .andExpect(status().isForbidden());
    }
}
