package nl.jimkaplan.autotrader.tradingview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import nl.jimkaplan.autotrader.service.TradingService;
import nl.jimkaplan.autotrader.tradingview.model.TradingViewAlertRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller for handling TradingView webhook requests.
 * Receives alerts from TradingView and processes them.
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
@Tag(name = "TradingView Webhook", description = "API for receiving and processing TradingView alerts")
public class TradingViewWebhookController {

    private final BotConfigurationService botConfigurationService;
    private final TradingService tradingService;
    private final List<String> ALLOWED_IPS = List.of(
            "52.89.214.238",
            "34.212.75.30",
            "54.218.53.128",
            "52.32.178.7");

    /**
     * Handles webhook requests from TradingView.
     *
     * @param apiKey      The webhook API key for authentication
     * @param request     The alert request from TradingView
     * @param httpRequest The HTTP request object used for IP validation
     * @return ResponseEntity with appropriate status code
     */
    @Operation(
            summary = "Process TradingView alert",
            description = "Receives and processes alerts from TradingView to execute trading actions. " +
                          "Validates the request based on IP address and API key before processing."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Alert processed successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request parameters or alert data",
                    content = @Content(
                            mediaType = "text/plain",
                            schema = @Schema(type = "string")
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized - Either IP address not in allowed list or invalid API key",
                    content = @Content(
                            mediaType = "text/plain",
                            schema = @Schema(type = "string")
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Server error processing the alert",
                    content = @Content(
                            mediaType = "text/plain",
                            schema = @Schema(type = "string")
                    )
            )
    })
    @PostMapping("/tradingview")
    public ResponseEntity<?> handleWebhook(
            @Parameter(description = "API key for authentication", allowEmptyValue = true)
            @RequestHeader("X-API-KEY") String apiKey,

            @Parameter(description = "Alert details from TradingView", required = true)
            @RequestBody TradingViewAlertRequest request,

            @Parameter(description = "HTTP request object used for IP validation", hidden = true)
            HttpServletRequest httpRequest
    ) {
        log.info("Received TradingView alert for bot: {}, ticker: {}, action: {}",
                request.getBotId(), request.getTicker(), request.getAction());

        ValidationResult validationResult = validateRequest(request, httpRequest, apiKey);
        return processValidationResult(validationResult, request, httpRequest);
    }

    // This method is protected to allow overriding in tests
    protected ResponseEntity<?> processValidationResult(ValidationResult validationResult,
                                                        TradingViewAlertRequest request,
                                                        HttpServletRequest httpRequest) {
        // Handle null validation result (should never happen in production, but we handle it for robustness)
        if (validationResult == null) {
            log.error("Error processing TradingView alert: validation result is null");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing alert for bot ID: " + request.getBotId());
        }

        switch (validationResult) {
            case IP_NOT_ALLOWED:
                log.warn("Unauthorized access attempt from IP: {}", httpRequest.getRemoteAddr());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized access");
            case INVALID_API_KEY:
                log.warn("Invalid API key for bot: {}", request.getBotId());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid API key");
            case VALID:
                try {
                    // Process the alert
                    tradingService.validateAndProcessAlert(request);
                    return ResponseEntity.ok().build();
                } catch (IllegalArgumentException e) {
                    // Bad request (invalid input)
                    log.warn("Bad request: {}", e.getMessage());
                    return ResponseEntity.badRequest().body(e.getMessage());
                } catch (Exception e) {
                    // Server error
                    log.error("Error processing TradingView alert", e);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("Error processing alert: " + e.getMessage());
                }
            default:
                log.error("Error processing TradingView alert: unexpected validation result");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Error processing alert for bot ID: " + request.getBotId());
        }
    }

    ValidationResult validateRequest(TradingViewAlertRequest tradingViewAlertRequest,
                                     HttpServletRequest httpServletRequest, String apiKey) {

        String remoteAddr = httpServletRequest.getRemoteAddr();
        if (ALLOWED_IPS.contains(remoteAddr)) {
            return ValidationResult.VALID;
        } else if (isLocalhost(remoteAddr)) {
            // If request origin is localhost, validate the API key
            if (botConfigurationService.validateWebhookApiKey(tradingViewAlertRequest.getBotId(), apiKey)) {
                return ValidationResult.VALID;
            } else {
                return ValidationResult.INVALID_API_KEY;
            }
        } else {
            return ValidationResult.IP_NOT_ALLOWED;
        }
    }

    // Enum for possible results of validation
    public enum ValidationResult {
        VALID,
        IP_NOT_ALLOWED,
        INVALID_API_KEY
    }

    private boolean isLocalhost(String remoteAddr) {
        return remoteAddr.equals("127.0.0.1") || remoteAddr.equals("0:0:0:0:0:0:0:1") || remoteAddr.equals("::1");
    }
}


