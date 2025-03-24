package nl.jimkaplan.autotrader.tradingview.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.service.TradingService;
import nl.jimkaplan.autotrader.tradingview.model.TradingViewAlertRequest;
import nl.jimkaplan.autotrader.tradingview.service.BotConfigurationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for handling TradingView webhook requests.
 * Receives alerts from TradingView and processes them.
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class TradingViewWebhookController {

    private final BotConfigurationService botConfigurationService;
    private final TradingService tradingService;

    /**
     * Handles webhook requests from TradingView.
     *
     * @param apiKey  The webhook API key for authentication
     * @param request The alert request from TradingView
     * @return ResponseEntity with appropriate status code
     */
    @PostMapping("/tradingview")
    public ResponseEntity<?> handleWebhook(
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody TradingViewAlertRequest request
    ) {
        log.info("Received TradingView alert for bot: {}, ticker: {}, action: {}",
                request.getBotId(), request.getTicker(), request.getAction());

        // Validate API key
        if (!botConfigurationService.validateWebhookApiKey(request.getBotId(), apiKey)) {
            log.warn("Invalid API key for bot: {}", request.getBotId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid API key");
        }

        try {
            // Process the alert
            tradingService.processAlert(request);
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
    }
}