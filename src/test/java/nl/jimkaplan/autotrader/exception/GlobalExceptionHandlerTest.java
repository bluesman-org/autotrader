package nl.jimkaplan.autotrader.exception;

import nl.jimkaplan.autotrader.model.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    void handleIllegalArgumentException_returnsBadRequestWithMessageAndPath() {
        // Arrange
        ServletWebRequest request = new ServletWebRequest(
                new MockHttpServletRequest("GET", "/autotrader/api/bots/abc/transactions"));

        // Act
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleIllegalArgumentException(
                new IllegalArgumentException("Bot ID must be exactly 6 characters long and contain only letters and numbers"),
                request);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getBody().getStatus());
        assertEquals("Bot ID must be exactly 6 characters long and contain only letters and numbers",
                response.getBody().getMessage());
        assertEquals("/autotrader/api/bots/abc/transactions", response.getBody().getPath());
    }
}
