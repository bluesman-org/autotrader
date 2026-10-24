package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.service.PushNotificationService;
import nl.jimkaplan.autotrader.strategy.service.PushSettingsService;
import nl.jimkaplan.autotrader.strategy.service.PushSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = PushController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "encryption.master-key=dGVzdC1rZXk="
})
class PushControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PushSettingsService pushSettingsService;
    @MockitoBean
    private PushSubscriptionService pushSubscriptionService;
    @MockitoBean
    private PushNotificationService pushNotificationService;

    @Test
    void thePublicKeyIsRawBytes() throws Exception {
        when(pushSettingsService.publicKey()).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/push/public-key"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void aSubscriptionIsStoredWithTheBrowserAgent() throws Exception {
        mockMvc.perform(post("/api/push/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "test-agent")
                        .content("""
                                {"endpoint":"https://push.example.com/abc",
                                 "keys":{"p256dh":"the-key","auth":"the-auth"}}
                                """))
                .andExpect(status().isCreated());

        verify(pushSubscriptionService).subscribe(any(PushSubscriptionRequest.class), eq("test-agent"));
    }

    @Test
    void unsubscribingRemovesTheEndpoint() throws Exception {
        mockMvc.perform(delete("/api/push/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://push.example.com/abc\"}"))
                .andExpect(status().isNoContent());

        verify(pushSubscriptionService).unsubscribe("https://push.example.com/abc");
    }

    @Test
    void aTestPushReportsHowManyDevicesAcceptedIt() throws Exception {
        when(pushNotificationService.sendTest()).thenReturn(2);

        mockMvc.perform(post("/api/push/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(2));
    }

    @Test
    void aTestPushWithoutASubscriptionIsABadRequest() throws Exception {
        doThrow(new IllegalArgumentException("No push subscription is registered"))
                .when(pushNotificationService).sendTest();

        mockMvc.perform(post("/api/push/test"))
                .andExpect(status().isBadRequest());
    }
}
