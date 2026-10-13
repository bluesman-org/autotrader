package nl.jimkaplan.autotrader.exception;

import nl.jimkaplan.autotrader.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

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
     * Handle IllegalArgumentException.
     * Intended for request validation errors thrown by controllers and services for client-caused
     * problems. Internal code must not throw IllegalArgumentException for server-side failures,
     * or the resulting 400 would misclassify a bug as a bad request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
        log.warn("Invalid request: {}", ex.getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                path
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
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
     * Handle unreadable request bodies: malformed JSON, or enum values the model does not know.
     * Without this, the catch-all handler would answer 500 for what is a client error.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadableException(
            HttpMessageNotReadableException ex, WebRequest request) {
        log.warn("Malformed request body: {}", ex.getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.BAD_REQUEST,
                "Malformed request body",
                path
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handle request parameter binding errors: a non-numeric number, or a missing required
     * parameter. Spring's own resolvers map these to 400, but this advice's catch-all would
     * answer 500 first.
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleInvalidRequestParameter(Exception ex, WebRequest request) {
        log.warn("Invalid request parameter: {}", ex.getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.BAD_REQUEST,
                "Invalid request parameter",
                path
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handle unsupported HTTP methods, which Spring maps to 405 by default.
     * Carries the {@code Allow} header with the methods the endpoint does support, matching the
     * header Spring's own resolver sets for this exception.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex, WebRequest request) {
        log.warn("Method not allowed: {}", ex.getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED,
                "Method not allowed",
                path
        );

        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> supportedMethods = ex.getSupportedHttpMethods();
        if (supportedMethods != null && !supportedMethods.isEmpty()) {
            headers.set(HttpHeaders.ALLOW, supportedMethods.stream()
                    .map(HttpMethod::name)
                    .collect(Collectors.joining(", ")));
        }

        return new ResponseEntity<>(errorResponse, headers, HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handle unsupported request media types, which Spring maps to 415 by default.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException ex, WebRequest request) {
        log.warn("Unsupported media type: {}", ex.getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported media type",
                path
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * Handle unknown paths, which Spring maps to 404 by default.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(
            NoResourceFoundException ex, WebRequest request) {
        log.warn("No resource found: {}", ex.getMessage());

        String path = getRequestPath(request);
        ErrorResponse errorResponse = ErrorResponse.of(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                path
        );

        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /**
     * Handle all other exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, WebRequest request) {
        // Pass the exception itself, so the stack trace is logged; building it from ex.getCause()
        // broke the handler for cause-less exceptions (most NPEs) and logged no trace.
        log.error("Unexpected error occurred. Message: {}.", ex.getMessage(), ex);

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
