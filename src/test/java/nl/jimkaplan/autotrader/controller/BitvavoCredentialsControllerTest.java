package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The credentials API: it reports the masked state and stores a replacement, and it never returns
 * the key or the secret.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = BitvavoCredentialsController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk="
})
class BitvavoCredentialsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BitvavoCredentialsService credentialsService;

    @Test
    void get_reportsTheMaskedState() throws Exception {
        when(credentialsService.view()).thenReturn(new BitvavoCredentialsResponse(true, "…1234", 7L,
                Instant.parse("2026-10-16T08:00:00Z")));

        mockMvc.perform(get("/api/bitvavo-credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKeyHint").value("…1234"))
                .andExpect(jsonPath("$.operatorId").value(7))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.apiSecret").doesNotExist());
    }

    @Test
    void get_reportsNothingConfigured() throws Exception {
        when(credentialsService.view()).thenReturn(new BitvavoCredentialsResponse(false, null, null, null));

        mockMvc.perform(get("/api/bitvavo-credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void put_storesTheKeyAndReturnsTheMaskedView() throws Exception {
        when(credentialsService.view()).thenReturn(new BitvavoCredentialsResponse(true, "…9999", 7L, null));

        mockMvc.perform(put("/api/bitvavo-credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"new-key\",\"apiSecret\":\"new-secret\",\"operatorId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKeyHint").value("…9999"));

        verify(credentialsService).save("new-key", "new-secret", 7L);
    }

    @Test
    void put_rejectsARequestWithoutAKey() throws Exception {
        when(credentialsService.save(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("The API key and its secret are both required"));

        mockMvc.perform(put("/api/bitvavo-credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiSecret\":\"secret\"}"))
                .andExpect(status().isBadRequest());
    }
}
