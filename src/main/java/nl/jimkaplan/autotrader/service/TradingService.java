package nl.jimkaplan.autotrader.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountBalanceResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetPriceResponse;
import nl.jimkaplan.autotrader.model.Order;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.model.document.Position;
import nl.jimkaplan.autotrader.tradingview.model.TradingViewAlertRequest;
import nl.jimkaplan.autotrader.tradingview.model.document.TradingViewAlert;
import nl.jimkaplan.autotrader.tradingview.service.TradingViewAlertService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Service for processing TradingView alerts and executing trades.
 * Handles the business logic for validating alerts, checking balances,
 * and placing orders on Bitvavo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingService {

    private final BotConfigurationService botConfigurationService;
    private final TradingViewAlertService tradingViewAlertService;
    private final OrderService orderService;
    private final PositionService positionService;
    private final BitvavoApiClient bitvavoApiClient;

    // Minimum EUR amount for trades
    private static final double MIN_EUR_AMOUNT = 5.0;

    /**
     * Process a TradingView alert.
     *
     * @param request The alert request from TradingView
     * @throws IllegalArgumentException if the request is invalid
     */
    public void processAlert(TradingViewAlertRequest request) {
        // Validate request
        validateRequest(request);

        // Get bot configuration
        BotConfiguration botConfig = getBotConfiguration(request.getBotId());

        // Log the alert
        saveAlert(request);

        // Verify ticker matches bot's configured trading pair
        if (!request.getTicker().equals(botConfig.getTradingPair())) {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Ticker mismatch: {0} does not match configured trading pair: {1}",
                            request.getTicker(), botConfig.getTradingPair())
            );
        }

        // Verify ticker is EUR-based
        if (!isEurBasedTicker(request.getTicker())) {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Unsupported ticker: {0}. Only EUR-based trading pairs are supported in v1.",
                            request.getTicker())
            );
        }

        // Process the alert based on action
        if ("buy".equalsIgnoreCase(request.getAction())) {
            processBuySignal(request, botConfig);
        } else if ("sell".equalsIgnoreCase(request.getAction())) {
            processSellSignal(request, botConfig);
        } else {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Invalid action: {0}. Supported actions are ''buy'' and ''sell''.",
                            request.getAction())
            );
        }
    }

    /**
     * Validate the TradingView alert request.
     *
     * @param request The alert request to validate
     * @throws IllegalArgumentException if the request is invalid
     */
    private void validateRequest(TradingViewAlertRequest request) {
        if (request.getBotId() == null || request.getBotId().isEmpty()) {
            throw new IllegalArgumentException("Bot ID is required");
        }

        if (request.getTicker() == null || request.getTicker().isEmpty()) {
            throw new IllegalArgumentException("Ticker is required");
        }

        if (request.getAction() == null || request.getAction().isEmpty()) {
            throw new IllegalArgumentException("Action is required");
        }

        if (request.getTimestamp() == null || request.getTimestamp().isEmpty()) {
            throw new IllegalArgumentException("Timestamp is required");
        }

        // Validate timestamp format
        try {
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(request.getTimestamp());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid timestamp format. Expected format: yyyy-MM-ddTHH:mm:ssZ");
        }
    }

    /**
     * Save the TradingView alert to the database.
     *
     * @param request The alert request
     */
    private void saveAlert(TradingViewAlertRequest request) {
        TradingViewAlert alert = TradingViewAlert.builder()
                .botId(request.getBotId())
                .ticker(request.getTicker())
                .action(request.getAction())
                .timestamp(Instant.parse(request.getTimestamp()))
                .build();

        tradingViewAlertService.saveAlert(alert);
    }

    /**
     * Get the bot configuration for the specified bot ID.
     *
     * @param botId The bot ID
     * @return The bot configuration
     * @throws IllegalArgumentException if the bot configuration is not found
     */
    private BotConfiguration getBotConfiguration(String botId) {
        return botConfigurationService.getBotConfiguration(botId)
                .orElseThrow(() -> new IllegalArgumentException("Bot configuration not found: " + botId));
    }

    /**
     * Check if a ticker is EUR-based.
     *
     * @param ticker The ticker to check
     * @return true if the ticker is EUR-based, false otherwise
     */
    private boolean isEurBasedTicker(String ticker) {
        return ticker.endsWith("EUR");
    }

    /**
     * Process a buy signal.
     *
     * @param request   The alert request
     * @param botConfig The bot configuration
     */
    private void processBuySignal(TradingViewAlertRequest request, BotConfiguration botConfig) {
        log.info("Processing buy signal for bot: {}, ticker: {}, dryRun: {}", 
                botConfig.getBotId(), request.getTicker(), request.getDryRun());

        try {
            // Check EUR balance
            double eurBalance = getEurBalance(botConfig);
            log.info("EUR balance: {}", eurBalance);

            if (eurBalance < MIN_EUR_AMOUNT) {
                String errorMessage = MessageFormat.format(
                        "Insufficient EUR balance: {0} EUR. Minimum required: {1} EUR.",
                        eurBalance,
                        MIN_EUR_AMOUNT);
                log.warn(errorMessage);
                saveFailedOrder(botConfig.getBotId(), request.getTicker(), errorMessage);
                return;
            }

            // Create market buy order
            CreateOrderRequest orderRequest = CreateOrderRequest.builder()
                    .market(request.getTicker())
                    .side("buy")
                    .orderType("market")
                    .amountQuote(BigDecimal.valueOf(eurBalance))
                    .build();

            CreateOrderResponse orderResponse;
            String orderId;

            // Check if this is a dry run
            if (Boolean.TRUE.equals(request.getDryRun())) {
                // Skip sending the order to Bitvavo in dry run mode
                log.info("DRY RUN: Skipping sending buy order to Bitvavo");
                // Generate a dummy order ID for dry run
                orderId = "dry-run-" + System.currentTimeMillis();
            } else {
                // Send the order to Bitvavo in normal mode
                orderResponse = bitvavoApiClient.post(
                        "/order", orderRequest, CreateOrderResponse.class, botConfig.getApiKey(), botConfig.getApiSecret());
                orderId = orderResponse.getOrderId().toString();
                log.info("Buy order placed successfully: {}", orderId);
            }

            // Save order to database
            Order order = Order.builder()
                    .botId(botConfig.getBotId())
                    .orderId(orderId)
                    .ticker(request.getTicker())
                    .timestamp(Instant.now())
                    .status("COMPLETED")
                    .build();

            orderService.saveOrder(order);

            // Update position
            updatePosition(botConfig.getBotId(), request.getTicker(), "OPEN");

        } catch (Exception e) {
            log.error("Error processing buy signal", e);
            saveFailedOrder(botConfig.getBotId(), request.getTicker(), e.getMessage());
            throw new RuntimeException("Error processing buy signal: " + e.getMessage(), e);
        }
    }

    /**
     * Process a sell signal.
     *
     * @param request   The alert request
     * @param botConfig The bot configuration
     */
    private void processSellSignal(TradingViewAlertRequest request, BotConfiguration botConfig) {
        log.info("Processing sell signal for bot: {}, ticker: {}, dryRun: {}", 
                botConfig.getBotId(), request.getTicker(), request.getDryRun());

        try {
            // Extract asset from ticker (e.g., "BTC" from "BTCEUR")
            String asset = request.getTicker().replace("EUR", "");

            // Check asset balance
            double assetBalance = getAssetBalance(botConfig, asset);
            log.info("{} balance: {}", asset, assetBalance);

            // Get asset price
            double assetPrice = getAssetPrice(request.getTicker(), botConfig);
            log.info("{} price: {} EUR", asset, assetPrice);

            // Calculate asset worth in EUR
            double assetWorth = assetBalance * assetPrice;
            log.info("{} worth: {} EUR", asset, assetWorth);

            if (assetWorth < MIN_EUR_AMOUNT) {
                String errorMessage = MessageFormat.format(
                        "Insufficient {0} balance worth: {1} EUR. Minimum required: {2} EUR.",
                        asset, assetWorth, MIN_EUR_AMOUNT);
                log.warn(errorMessage);
                saveFailedOrder(botConfig.getBotId(), request.getTicker(), errorMessage);
                return;
            }

            // Create market sell order
            CreateOrderRequest orderRequest = CreateOrderRequest.builder()
                    .market(request.getTicker())
                    .side("sell")
                    .orderType("market")
                    .amount(BigDecimal.valueOf(assetBalance))
                    .build();

            CreateOrderResponse orderResponse;
            String orderId;

            // Check if this is a dry run
            if (Boolean.TRUE.equals(request.getDryRun())) {
                // Skip sending the order to Bitvavo in dry run mode
                log.info("DRY RUN: Skipping sending sell order to Bitvavo");
                // Generate a dummy order ID for dry run
                orderId = "dry-run-" + System.currentTimeMillis();
            } else {
                // Send the order to Bitvavo in normal mode
                orderResponse = bitvavoApiClient.post(
                        "/order", orderRequest, CreateOrderResponse.class, botConfig.getApiKey(), botConfig.getApiSecret());
                orderId = orderResponse.getOrderId().toString();
                log.info("Sell order placed successfully: {}", orderId);
            }

            // Save order to database
            Order order = Order.builder()
                    .botId(botConfig.getBotId())
                    .orderId(orderId)
                    .ticker(request.getTicker())
                    .timestamp(Instant.now())
                    .status("COMPLETED")
                    .build();

            orderService.saveOrder(order);

            // Update position
            updatePosition(botConfig.getBotId(), request.getTicker(), "CLOSED");

        } catch (Exception e) {
            log.error("Error processing sell signal", e);
            saveFailedOrder(botConfig.getBotId(), request.getTicker(), e.getMessage());
            throw new RuntimeException("Error processing sell signal: " + e.getMessage(), e);
        }
    }

    /**
     * Get the EUR balance for a bot.
     *
     * @param botConfig The bot configuration
     * @return The EUR balance
     */
    private double getEurBalance(BotConfiguration botConfig) {
        GetAccountBalanceResponse balanceResponse = bitvavoApiClient.get(
                "/balance?symbol=EUR", GetAccountBalanceResponse.class, botConfig.getApiKey(), botConfig.getApiSecret());
        return balanceResponse.getAvailable().doubleValue();
    }

    /**
     * Get the asset balance for a bot.
     *
     * @param botConfig The bot configuration
     * @param asset     The asset symbol (e.g., "BTC")
     * @return The asset balance
     */

    private double getAssetBalance(BotConfiguration botConfig, String asset) {
        GetAccountBalanceResponse balanceResponse = bitvavoApiClient.get(
                "/balance?symbol=" + asset, GetAccountBalanceResponse.class, botConfig.getApiKey(), botConfig.getApiSecret());
        return balanceResponse.getAvailable().doubleValue();
    }

    /**
     * Get the price of an asset.
     *
     * @param ticker The ticker (e.g., "BTCEUR")
     * @return The asset price in EUR
     */
    private double getAssetPrice(String ticker, BotConfiguration botConfig) {
        GetPriceResponse priceResponse = bitvavoApiClient.get(
                "/ticker/price?market=" + ticker, GetPriceResponse.class, botConfig.getApiKey(), botConfig.getApiSecret());
        return priceResponse.getPrice().doubleValue();
    }

    /**
     * Save a failed order to the database.
     *
     * @param botId        The bot ID
     * @param ticker       The ticker
     * @param errorMessage The error message
     */
    private void saveFailedOrder(String botId, String ticker, String errorMessage) {
        Order order = Order.builder()
                .botId(botId)
                .ticker(ticker)
                .timestamp(Instant.now())
                .status("FAILED")
                .errorMessage(errorMessage)
                .build();

        orderService.saveOrder(order);
    }

    /**
     * Update the position status for a bot and ticker.
     *
     * @param botId  The bot ID
     * @param ticker The ticker
     * @param status The new status
     */
    private void updatePosition(String botId, String ticker, String status) {
        // Check if position exists
        Optional<Position> existingPosition = positionService.getPositionByBotIdAndTickerAndStatus(botId, ticker, "OPEN");

        if (existingPosition.isPresent()) {
            // Update existing position
            Position position = existingPosition.get();
            position.setStatus(status);
            positionService.savePosition(position);
        } else if ("OPEN".equals(status)) {
            // Create new position if opening
            Position position = Position.builder()
                    .botId(botId)
                    .ticker(ticker)
                    .status(status)
                    .build();
            positionService.savePosition(position);
        }
    }
}
