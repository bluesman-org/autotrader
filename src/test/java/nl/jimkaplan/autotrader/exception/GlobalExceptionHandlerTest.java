package nl.jimkaplan.autotrader.exception;

import nl.jimkaplan.autotrader.model.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private static final String PATH = "/autotrader/api/bots/abc/transactions";

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    void handleIllegalArgumentException_returnsBadRequestWithMessageAndPath() {
        // Arrange
        ServletWebRequest request = request();

        // Act
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleIllegalArgumentException(
                new IllegalArgumentException("Bot ID must be exactly 6 characters long and contain only letters and numbers"),
                request);

        // Assert
        assertError(response, HttpStatus.BAD_REQUEST,
                "Bot ID must be exactly 6 characters long and contain only letters and numbers");
    }

    @Test
    void handleIllegalStateException_returnsInternalServerErrorWithoutDetails() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleIllegalStateException(
                new IllegalStateException("missing configuration"), request());

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal error occurred.");
    }

    @Test
    void handleHttpClientErrorException_keepsTheClientStatus() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleHttpClientErrorException(
                new HttpClientErrorException(HttpStatus.NOT_FOUND, "Not Found"), request());

        assertError(response, HttpStatus.NOT_FOUND, "External API client error: Not Found");
    }

    @Test
    void handleHttpServerErrorException_mapsToBadGateway() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleHttpServerErrorException(
                new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"), request());

        assertError(response, HttpStatus.BAD_GATEWAY, "External API server error: Internal Server Error");
    }

    @Test
    void handleResourceAccessException_mapsToServiceUnavailable() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleResourceAccessException(
                new ResourceAccessException("Connection refused"), request());

        assertError(response, HttpStatus.SERVICE_UNAVAILABLE, "External service unavailable: Connection refused");
    }

    @Test
    void handleRestClientException_mapsToInternalServerError() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleRestClientException(
                new RestClientException("Unknown error"), request());

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "External API communication error: Unknown error");
    }

    @Test
    void handleNoSuchElementException_mapsToNotFound() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleNoSuchElementException(
                new NoSuchElementException("No value present"), request());

        assertError(response, HttpStatus.NOT_FOUND, "Resource not found: No value present");
    }

    @Test
    void handleGenericException_withoutCause_returnsTheStandardResponse() {
        // Regression: building the log message from ex.getCause() threw an NPE for the (common)
        // cause-less exception, bypassing the standard response entirely.
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleGenericException(
                new NullPointerException("boom"), request());

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    @Test
    void handleGenericException_withCause_returnsTheStandardResponse() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleGenericException(
                new IllegalStateException("outer", new RuntimeException("inner")), request());

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    @Test
    void handleMessageNotReadableException_returnsBadRequest() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleMessageNotReadableException(
                new HttpMessageNotReadableException("unknown enum value"), request());

        assertError(response, HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    @Test
    void handleInvalidRequestParameter_returnsBadRequest() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleInvalidRequestParameter(
                new MethodArgumentTypeMismatchException("abc", Integer.class, "limit", null, null), request());

        assertError(response, HttpStatus.BAD_REQUEST, "Invalid request parameter");
    }

    @Test
    void handleInvalidRequestParameter_withMissingParameter_returnsBadRequest() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleInvalidRequestParameter(
                new MissingServletRequestParameterException("limit", "int"), request());

        assertError(response, HttpStatus.BAD_REQUEST, "Invalid request parameter");
    }

    @Test
    void handleMethodNotSupportedException_returnsMethodNotAllowed() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleMethodNotSupportedException(
                new HttpRequestMethodNotSupportedException("PUT"), request());

        assertError(response, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        assertNull(response.getHeaders().getFirst(HttpHeaders.ALLOW));
    }

    @Test
    void handleMethodNotSupportedException_withSupportedMethods_setsAllowHeader() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleMethodNotSupportedException(
                new HttpRequestMethodNotSupportedException("PATCH", List.of("GET", "PUT")), request());

        assertError(response, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        assertEquals("GET, PUT", response.getHeaders().getFirst(HttpHeaders.ALLOW));
    }

    @Test
    void handleMethodNotSupportedException_withoutSupportedMethods_omitsAllowHeader() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleMethodNotSupportedException(
                new HttpRequestMethodNotSupportedException("PATCH", List.of()), request());

        assertError(response, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        assertNull(response.getHeaders().getFirst(HttpHeaders.ALLOW));
    }

    @Test
    void handleMediaTypeNotSupportedException_returnsUnsupportedMediaType() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleMediaTypeNotSupportedException(
                new HttpMediaTypeNotSupportedException("text/plain"), request());

        assertError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
    }

    @Test
    void handleMediaTypeNotSupportedException_withSupportedMediaTypes_setsAcceptHeader() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleMediaTypeNotSupportedException(
                new HttpMediaTypeNotSupportedException("text/plain", List.of(MediaType.APPLICATION_JSON)), request());

        assertError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
        assertEquals("application/json", response.getHeaders().getFirst(HttpHeaders.ACCEPT));
    }

    @Test
    void handleMediaTypeNotAcceptableException_returnsNotAcceptableWithoutABody() {
        ResponseEntity<Void> response = globalExceptionHandler.handleMediaTypeNotAcceptableException(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), request());

        assertEquals(HttpStatus.NOT_ACCEPTABLE, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void handleNoResourceFoundException_returnsNotFound() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleNoResourceFoundException(
                new NoResourceFoundException(HttpMethod.GET, "/api/nope"), request());

        assertError(response, HttpStatus.NOT_FOUND, "Resource not found");
    }

    @Test
    void handler_withNonServletWebRequest_returnsEmptyPath() {
        WebRequest plainRequest = mock(WebRequest.class);

        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleNoSuchElementException(
                new NoSuchElementException("No value present"), plainRequest);

        assertNotNull(response.getBody());
        assertEquals("", response.getBody().getPath());
    }

    private static ServletWebRequest request() {
        return new ServletWebRequest(new MockHttpServletRequest("GET", PATH));
    }

    private static void assertError(ResponseEntity<ErrorResponse> response, HttpStatus status, String message) {
        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(status.value(), response.getBody().getStatus());
        assertEquals(message, response.getBody().getMessage());
        assertEquals(PATH, response.getBody().getPath());
    }
}
