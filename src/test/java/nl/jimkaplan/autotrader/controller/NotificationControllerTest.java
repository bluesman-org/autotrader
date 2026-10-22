package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.strategy.service.LiveAlertService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private LiveAlertService alerts;

    @InjectMocks
    private NotificationController controller;

    @Test
    void testSendsTheMessageAndReportsIt() {
        org.springframework.http.ResponseEntity<NotificationController.NotificationTestResponse> response =
                controller.test();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().message().contains("test alert"));
        verify(alerts).sendTest();
    }

    @Test
    void aMissingConfigurationPropagates() {
        doThrow(new IllegalStateException("No SMTP server is configured yet"))
                .when(alerts).sendTest();

        assertThrows(IllegalStateException.class, () -> controller.test());
    }
}
