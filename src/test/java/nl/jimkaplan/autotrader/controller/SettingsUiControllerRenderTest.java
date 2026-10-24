package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.model.document.Market;
import nl.jimkaplan.autotrader.service.AppUserService;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.service.MailSettingsService;
import nl.jimkaplan.autotrader.service.MarketService;
import nl.jimkaplan.autotrader.strategy.service.LiveAlertService;
import nl.jimkaplan.autotrader.strategy.service.PushNotificationService;
import nl.jimkaplan.autotrader.strategy.service.PushSettingsService;
import nl.jimkaplan.autotrader.strategy.service.PushSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The settings page renders the masked key state and posts a replacement; the secret never travels
 * back into the page.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = SettingsUiController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk=",
        "autotrader.ui.time-zone=UTC"
})
class SettingsUiControllerRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BitvavoCredentialsService credentialsService;
    @MockitoBean
    private MailSettingsService mailSettingsService;
    @MockitoBean
    private MarketService marketService;
    @MockitoBean
    private AppUserService users;
    @MockitoBean
    private LiveAlertService alerts;
    @MockitoBean
    private PushSettingsService pushSettingsService;
    @MockitoBean
    private PushSubscriptionService pushSubscriptionService;
    @MockitoBean
    private PushNotificationService pushNotificationService;

    @BeforeEach
    void setUp() {
        when(mailSettingsService.view()).thenReturn(
                new MailSettingsResponse(false, null, null, null, null, true, null));
        when(users.find(any())).thenReturn(Optional.empty());
        when(marketService.all()).thenReturn(List.of());
    }

    @Test
    void pageShowsTheConfiguredKeyMasked() throws Exception {
        when(credentialsService.view()).thenReturn(new BitvavoCredentialsResponse(true, "…1234", 7L,
                Instant.parse("2026-10-16T08:00:00Z")));

        mockMvc.perform(get("/ui/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Settings")))
                .andExpect(content().string(containsString("Bitvavo key")))
                .andExpect(content().string(containsString("configured")))
                .andExpect(content().string(containsString("…1234")))
                .andExpect(content().string(containsString("operator id 7")))
                .andExpect(content().string(containsString("16 Oct 2026 08:00 (UTC)")))
                .andExpect(content().string(containsString("type=\"password\" name=\"apiKey\"")))
                .andExpect(content().string(containsString("type=\"password\" name=\"apiSecret\"")));
    }

    @Test
    void pageSaysWhenNoKeyIsConfigured() throws Exception {
        when(credentialsService.view()).thenReturn(new BitvavoCredentialsResponse(false, null, null, null));

        mockMvc.perform(get("/ui/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No key is configured yet.")));
    }

    @Test
    void postStoresTheSubmittedKey() throws Exception {
        mockMvc.perform(post("/ui/settings")
                        .param("apiKey", "new-key")
                        .param("apiSecret", "new-secret")
                        .param("operatorId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));

        verify(credentialsService).save("new-key", "new-secret", 7L);
    }

    @Test
    void theMailCardShowsWhatIsConfigured() throws Exception {
        when(credentialsService.view()).thenReturn(new BitvavoCredentialsResponse(false, null, null, null));
        when(mailSettingsService.view()).thenReturn(new MailSettingsResponse(true, "smtp.example.com",
                587, "mailer", "autotrader@example.com", true, Instant.parse("2026-10-16T09:00:00Z")));

        mockMvc.perform(get("/ui/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("smtp.example.com:587")))
                .andExpect(content().string(containsString("from autotrader@example.com")))
                .andExpect(content().string(containsString("STARTTLS")))
                .andExpect(content().string(containsString("Send test alert")))
                // The stored password never travels back into the page.
                .andExpect(content().string(not(containsString("smtp-password"))));
    }

    @Test
    void postSendsATestAlert() throws Exception {
        mockMvc.perform(post("/ui/settings/notifications/test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"))
                .andExpect(flash().attribute("message", containsString("test alert")));

        verify(alerts).sendTest();
    }

    @Test
    void postReportsATestAlertFailure() throws Exception {
        doThrow(new IllegalStateException("No SMTP server is configured yet"))
                .when(alerts).sendTest();

        mockMvc.perform(post("/ui/settings/notifications/test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"))
                .andExpect(flash().attribute("error", containsString("No SMTP server")));
    }

    @Test
    void postStoresTheMailSettings() throws Exception {
        mockMvc.perform(post("/ui/settings/mail")
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("username", "mailer")
                        .param("password", "smtp-password")
                        .param("fromAddress", "autotrader@example.com")
                        .param("startTls", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));

        verify(mailSettingsService).save("smtp.example.com", 587, "mailer", "smtp-password",
                "autotrader@example.com", true);
    }

    @Test
    void postKeepsTheSecretOutOfTheRedirectWhenTheKeyIsRejected() throws Exception {
        doThrow(new IllegalArgumentException("The API key and its secret are both required"))
                .when(credentialsService).save(any(), any(), any());

        mockMvc.perform(post("/ui/settings").param("apiKey", "only-key"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
    }

    @Test
    void theTradingPairsCardShowsTheCatalogue() throws Exception {
        when(credentialsService.view()).thenReturn(new BitvavoCredentialsResponse(false, null, null, null));
        when(marketService.all()).thenReturn(List.of(market("ETH-EUR")));

        mockMvc.perform(get("/ui/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Trading pairs")))
                .andExpect(content().string(containsString("ETH-EUR")))
                .andExpect(content().string(containsString("enabled")))
                .andExpect(content().string(containsString("trading")))
                .andExpect(content().string(containsString("discovered")))
                .andExpect(content().string(containsString("24 Sep 2021")))
                .andExpect(content().string(containsString("9 Oct 2026")));
    }

    @Test
    void postAddsATradingPair() throws Exception {
        when(marketService.add("ETH-EUR")).thenReturn(market("ETH-EUR"));

        mockMvc.perform(post("/ui/settings/markets").param("market", "ETH-EUR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));

        verify(marketService).add("ETH-EUR");
    }

    @Test
    void postReportsAnUnknownPair() throws Exception {
        when(marketService.add("NOPE-EUR"))
                .thenThrow(new IllegalArgumentException("Unknown market: NOPE-EUR"));

        mockMvc.perform(post("/ui/settings/markets").param("market", "NOPE-EUR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
    }

    @Test
    void postReportsWhenBitvavoCannotBeReachedForAPair() throws Exception {
        when(marketService.add("ETH-EUR")).thenThrow(new ResourceAccessException("down"));

        mockMvc.perform(post("/ui/settings/markets").param("market", "ETH-EUR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
    }

    @Test
    void postTogglesATradingPair() throws Exception {
        Market disabled = market("ETH-EUR");
        disabled.setEnabled(false);
        when(marketService.setEnabled("ETH-EUR", false)).thenReturn(disabled);

        mockMvc.perform(post("/ui/settings/markets/ETH-EUR/disable"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));

        verify(marketService).setEnabled("ETH-EUR", false);
    }

    @Test
    void postEnablesATradingPair() throws Exception {
        when(marketService.setEnabled("ETH-EUR", true)).thenReturn(market("ETH-EUR"));

        mockMvc.perform(post("/ui/settings/markets/ETH-EUR/enable"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));

        verify(marketService).setEnabled("ETH-EUR", true);
    }

    @Test
    void postRefreshesTheTradingPairs() throws Exception {
        when(marketService.discover()).thenReturn(new MarketService.Discovery(11, 11, 0, 0));

        mockMvc.perform(post("/ui/settings/markets/discover"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));

        verify(marketService).discover();
    }

    @Test
    void postReportsWhenBitvavoCannotBeReadForTheRefresh() throws Exception {
        when(marketService.discover()).thenThrow(new ResourceAccessException("down"));

        mockMvc.perform(post("/ui/settings/markets/discover"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
    }

    @Test
    void thePushCardShowsTheSubscribedDevices() throws Exception {
        when(credentialsService.view()).thenReturn(new BitvavoCredentialsResponse(false, null, null, null));
        when(pushSubscriptionService.count()).thenReturn(2L);
        when(pushSettingsService.configured()).thenReturn(true);

        mockMvc.perform(get("/ui/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Push notifications")))
                .andExpect(content().string(containsString("2 devices subscribed")))
                .andExpect(content().string(containsString("keys ready")))
                .andExpect(content().string(containsString("Enable on this device")))
                .andExpect(content().string(containsString("Send test push")))
                .andExpect(content().string(containsString("service-worker.js")))
                .andExpect(content().string(containsString("manifest.webmanifest")));
    }

    @Test
    void postSendsATestPush() throws Exception {
        when(pushNotificationService.sendTest()).thenReturn(2);

        mockMvc.perform(post("/ui/settings/push/test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"))
                .andExpect(flash().attribute("message", containsString("test push")))
                .andExpect(flash().attribute("message", containsString("2 devices")));

        verify(pushNotificationService).sendTest();
    }

    @Test
    void postReportsATestPushFailure() throws Exception {
        doThrow(new IllegalArgumentException("No push subscription is registered"))
                .when(pushNotificationService).sendTest();

        mockMvc.perform(post("/ui/settings/push/test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"))
                .andExpect(flash().attribute("error", containsString("No push subscription")));
    }

    private static Market market(String symbol) {
        return Market.builder()
                .market(symbol)
                .base("ETH")
                .quote("EUR")
                .enabled(true)
                .source(Market.SOURCE_DISCOVERED)
                .status("trading")
                .tradeCount(23)
                .firstTradedAt(Instant.parse("2021-09-24T08:00:00Z"))
                .lastTradedAt(Instant.parse("2026-10-09T09:00:00Z"))
                .addedTime(Instant.parse("2026-10-18T10:00:00Z"))
                .build();
    }
}
