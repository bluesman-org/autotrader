package nl.jimkaplan.autotrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

/**
 * Standard error response model for API errors.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    
    /**
     * HTTP status code
     */
    private int status;
    
    /**
     * Error message
     */
    private String message;
    
    /**
     * Error code (optional)
     */
    private String code;
    
    /**
     * Timestamp when the error occurred
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    
    /**
     * Path where the error occurred
     */
    private String path;
    
    /**
     * Factory method to create an error response with the given status and message.
     *
     * @param status HTTP status
     * @param message Error message
     * @param path Request path
     * @return ErrorResponse instance
     */
    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return ErrorResponse.builder()
                .status(status.value())
                .message(message)
                .path(path)
                .build();
    }
    
    /**
     * Factory method to create an error response with the given status, message, and code.
     *
     * @param status HTTP status
     * @param message Error message
     * @param code Error code
     * @param path Request path
     * @return ErrorResponse instance
     */
    public static ErrorResponse of(HttpStatus status, String message, String code, String path) {
        return ErrorResponse.builder()
                .status(status.value())
                .message(message)
                .code(code)
                .path(path)
                .build();
    }
}