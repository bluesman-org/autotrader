package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
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

    @Test
    void pageShowsTheConfiguredKeyMasked() throws Exception {
        when(credentialsService.view()).thenReturn(new BitvavoCredentialsResponse(true, "…1234", 7L,
                Instant.parse("2026-10-16T08:00:00Z")));
        when(credentialsService.get()).thenReturn(java.util.Optional.of(
                nl.jimkaplan.autotrader.model.document.BitvavoCredentials.builder()
                        .operatorId(7L)
                        .updatedTime(Instant.parse("2026-10-16T08:00:00Z"))
                        .build()));

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
        when(credentialsService.get()).thenReturn(java.util.Optional.empty());

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
    void postKeepsTheSecretOutOfTheRedirectWhenTheKeyIsRejected() throws Exception {
        doThrow(new IllegalArgumentException("The API key and its secret are both required"))
                .when(credentialsService).save(any(), any(), any());

        mockMvc.perform(post("/ui/settings").param("apiKey", "only-key"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/settings"));
    }
}
