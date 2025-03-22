package nl.jimkaplan.autotrader.exception;

import nl.jimkaplan.autotrader.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.util.NoSuchElementException;

/**
 * Global exception handler for the application.
 * Handles exceptions and returns standardized error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle IllegalStateException.
     * This is thrown when the application is in an illegal state, e.g., missing configuration.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateException(IllegalStateException ex, WebRequest request) {
        log.error("IllegalStateException occurred: {}", ex.getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "Internal error occurred.",
                path
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Handle HttpClientErrorException.
     * This is thrown when a REST client receives a 4xx response.
     */
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ErrorResponse> handleHttpClientErrorException(HttpClientErrorException ex, WebRequest request) {
        log.error("HTTP client error: {} - {}", ex.getStatusCode(), ex.getMessage());

        String path = getRequestPath(request);
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        ErrorResponse errorResponse = ErrorResponse.of(
                status, 
                "External API client error: " + ex.getStatusText(),
                path
        );

        return new ResponseEntity<>(errorResponse, status);
    }

    /**
     * Handle HttpServerErrorException.
     * This is thrown when a REST client receives a 5xx response.
     */
    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ErrorResponse> handleHttpServerErrorException(HttpServerErrorException ex, WebRequest request) {
        log.error("HTTP server error: {} - {}", ex.getStatusCode(), ex.getMessage());

        String path = getRequestPath(request);
        HttpStatus status = HttpStatus.BAD_GATEWAY; // Use BAD_GATEWAY for all server errors
        ErrorResponse errorResponse = ErrorResponse.of(
                status, 
                "External API server error: " + ex.getStatusText(),
                path
        );

        return new ResponseEntity<>(errorResponse, status);
    }

    /**
     * Handle ResourceAccessException.
     * This is thrown when a REST client cannot access a resource, e.g., connection timeout.
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleResourceAccessException(ResourceAccessException ex, WebRequest request) {
        log.error("Resource access error: {}", ex.getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.SERVICE_UNAVAILABLE, 
                "External service unavailable: " + ex.getMessage(),
                path
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Handle RestClientException.
     * This is a general exception for REST client errors.
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleRestClientException(RestClientException ex, WebRequest request) {
        log.error("REST client error: {}", ex.getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "External API communication error: " + ex.getMessage(),
                path
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Handle NoSuchElementException.
     * This is thrown when an element is not found.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchElementException(NoSuchElementException ex, WebRequest request) {
        log.error("Resource not found: {}", ex.getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.NOT_FOUND, 
                "Resource not found: " + ex.getMessage(),
                path
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /**
     * Handle all other exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, WebRequest request) {
        log.error("Unexpected error occurred. Message: {}. Cause: {}. ",
                ex.getMessage(),
                ex.getCause().getClass() + ex.getCause().getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "An unexpected error occurred",
                path
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Extract the request path from the WebRequest.
     */
    private String getRequestPath(WebRequest request) {
        if (request instanceof ServletWebRequest) {
            return ((ServletWebRequest) request).getRequest().getRequestURI();
        }
        return "";
    }
}
