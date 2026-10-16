package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.service.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The recovery API answers with the same sentence the page shows, for a known address and an
 * unknown one alike.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = AuthApiController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk="
})
class AuthApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordResetService recovery;

    @Test
    void asksForARecoveryMail() throws Exception {
        when(recovery.request("jim@example.com")).thenReturn(PasswordResetService.SENT_MESSAGE);

        mockMvc.perform(post("/api/password-recovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"jim@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(PasswordResetService.SENT_MESSAGE));
    }

    @Test
    void anUnknownAddressReadsTheSame() throws Exception {
        when(recovery.request(any())).thenReturn(PasswordResetService.SENT_MESSAGE);

        mockMvc.perform(post("/api/password-recovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(PasswordResetService.SENT_MESSAGE));
    }
}
