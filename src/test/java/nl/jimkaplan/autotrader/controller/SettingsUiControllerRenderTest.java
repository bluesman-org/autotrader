package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.service.AppUserService;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.service.MailSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
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
    private AppUserService users;

    @BeforeEach
    void setUp() {
        when(mailSettingsService.view()).thenReturn(
                new MailSettingsResponse(false, null, null, null, null, true, null));
        when(users.find(any())).thenReturn(Optional.empty());
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
                .andExpect(content().string(containsString("16 Oct 08:00 (UTC)")))
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
                // The stored password never travels back into the page.
                .andExpect(content().string(not(containsString("smtp-password"))));
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
}
