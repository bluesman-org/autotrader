package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.service.MailSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The mail-settings API: it reports the masked state and stores a replacement, and it never returns
 * the SMTP password.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = MailSettingsController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk="
})
class MailSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MailSettingsService mailSettingsService;

    @Test
    void get_reportsTheMaskedState() throws Exception {
        when(mailSettingsService.view()).thenReturn(new MailSettingsResponse(true, "smtp.example.com",
                587, "mailer", "autotrader@example.com", true,
                Instant.parse("2026-10-16T09:00:00Z")));

        mockMvc.perform(get("/api/mail-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.host").value("smtp.example.com"))
                .andExpect(jsonPath("$.port").value(587))
                .andExpect(jsonPath("$.fromAddress").value("autotrader@example.com"))
                .andExpect(jsonPath("$.startTls").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.encryptedPassword").doesNotExist());
    }

    @Test
    void get_reportsNothingConfigured() throws Exception {
        when(mailSettingsService.view()).thenReturn(
                new MailSettingsResponse(false, null, null, null, null, true, null));

        mockMvc.perform(get("/api/mail-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void put_storesTheSettingsAndAnswersMasked() throws Exception {
        when(mailSettingsService.view()).thenReturn(new MailSettingsResponse(true, "smtp.example.com",
                587, "mailer", "autotrader@example.com", true, null));

        mockMvc.perform(put("/api/mail-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host":"smtp.example.com","port":587,"username":"mailer",
                                 "password":"smtp-secret","fromAddress":"autotrader@example.com",
                                 "startTls":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(mailSettingsService).save("smtp.example.com", 587, "mailer", "smtp-secret",
                "autotrader@example.com", true);
    }

    @Test
    void put_reportsAMissingHostAsABadRequest() throws Exception {
        doThrow(new IllegalArgumentException("The SMTP host is required"))
                .when(mailSettingsService).save(any(), any(), any(), any(), any(), any(Boolean.class));

        mockMvc.perform(put("/api/mail-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAddress\":\"autotrader@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
}
