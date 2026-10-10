package nl.jimkaplan.autotrader.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountBalanceResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetMarketResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetPriceResponse;
import nl.jimkaplan.autotrader.model.Order;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.model.document.Position;
import nl.jimkaplan.autotrader.tradingview.model.TradingViewAlertRequest;
import nl.jimkaplan.autotrader.tradingview.model.document.TradingViewAlert;
import nl.jimkaplan.autotrader.tradingview.service.TradingViewAlertService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    // Fallback decimals to use when Bitvavo does not report them for a market
    private static final int FALLBACK_QUANTITY_DECIMALS = 8;
    private static final int FALLBACK_NOTIONAL_DECIMALS = 2;

    /**
     * Process a TradingView alert.
     *
     * @param request The alert request from TradingView
     * @throws IllegalArgumentException if the request is invalid
     */
    public void validateAndProcessAlert(TradingViewAlertRequest request) {
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

        // Verify the bot has an operator ID configured; Bitvavo requires it for every order
        if (botConfig.getOperatorId() == null) {
            throw new IllegalArgumentException(
                    MessageFormat.format(
                            "Bot configuration for bot ID {0} has no operator ID. Recreate the bot with an operator ID.",
                            botConfig.getBotId())
            );
        }

        // Process the alert based on action using switch expression
        switch (request.getAction().toLowerCase()) {
            case "buy" -> processBuySignal(request, botConfig);
            case "sell" -> processSellSignal(request, botConfig);
            default -> throw new IllegalArgumentException(
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
        // Use pattern matching for null/empty checks
        String botId = request.getBotId();
        if (botId == null || botId.isEmpty()) {
            throw new IllegalArgumentException("Bot ID is required");
        }

        String ticker = request.getTicker();
        if (ticker == null || ticker.isEmpty()) {
            throw new IllegalArgumentException("Ticker is required");
        }

        String action = request.getAction();
        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("Action is required");
        }

        String timestamp = request.getTimestamp();
        if (timestamp == null || timestamp.isEmpty()) {
            throw new IllegalArgumentException("Timestamp is required");
        }

        // Validate timestamp format
        try {
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(timestamp);
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
    boolean isEurBasedTicker(String ticker) {
        return ticker.endsWith("EUR");
    }

    /**
     * Process a buy signal.
     *
     * @param request   The alert request
     * @param botConfig The bot configuration
     */
    void processBuySignal(TradingViewAlertRequest request, BotConfiguration botConfig) {
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

            // Round the spendable amount down to the market's allowed decimals
            GetMarketResponse market = getMarketInfo(botConfig);
            BigDecimal amountQuote = BigDecimal.valueOf(eurBalance)
                    .setScale(resolveNotionalDecimals(market), RoundingMode.DOWN);

            if (amountQuote.signum() <= 0) {
                String errorMessage = "EUR amount too small for order precision";
                log.warn(errorMessage);
                saveFailedOrder(botConfig.getBotId(), request.getTicker(), errorMessage);
                return;
            }

            // Create market buy order
            CreateOrderRequest orderRequest = CreateOrderRequest.builder()
                    .market(toBitvavoMarket(request.getTicker()))
                    .side("buy")
                    .orderType("market")
                    .amountQuote(amountQuote)
                    .operatorId(botConfig.getOperatorId())
                    .build();

            CreateOrderResponse orderResponse;
            String orderId;
            String status;

            // Check if this is a dry run
            if (Boolean.TRUE.equals(request.getDryRun())) {
                // Skip sending the order to Bitvavo in dry run mode
                log.info("DRY RUN: Skipping sending buy order to Bitvavo");
                // Generate a dummy order ID for dry run
                orderId = "dry-run-" + System.currentTimeMillis();
                status = "COMPLETED";
                orderResponse = null;
            } else {
                // Send the order to Bitvavo in normal mode
                orderResponse = bitvavoApiClient.post(
                        "/order", orderRequest, CreateOrderResponse.class, botConfig.getApiKey(), botConfig.getApiSecret());
                orderId = orderResponse.getOrderId().toString();
                // Record the status the exchange actually returned (e.g. "new", "filled")
                status = (orderResponse.getStatus() != null && !orderResponse.getStatus().isEmpty())
                        ? orderResponse.getStatus()
                        : "COMPLETED";
                log.info("Buy order placed successfully: {}", orderId);
            }

            // Save order to database
            Instant orderTimestamp = (orderResponse != null && orderResponse.getCreated() != null)
                    ? Instant.ofEpochMilli(orderResponse.getCreated())
                    : Instant.now();

            Order order = Order.builder()
                    .botId(botConfig.getBotId())
                    .orderId(orderId)
                    .ticker(request.getTicker())
                    .timestamp(orderTimestamp)
                    .status(status)
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
            // Normalise the ticker once (e.g. "BTCEUR" or "BTC-EUR" to "BTC-EUR") and derive
            // the asset symbol (e.g. "BTC") for the balance check
            String market = toBitvavoMarket(request.getTicker());
            String asset = market.replace("-EUR", "");

            // Check asset balance
            double assetBalance = getAssetBalance(botConfig, asset);
            log.info("{} balance: {}", asset, assetBalance);

            if (assetBalance == 0.0) {
                String errorMessage = MessageFormat.format(
                        "Insufficient {0} balance: {1}.",
                        asset, assetBalance);
                log.warn(errorMessage);
                saveFailedOrder(botConfig.getBotId(), request.getTicker(), errorMessage);
                return;
            }

            // Round the sell quantity down to the market's allowed decimals
            GetMarketResponse marketInfo = getMarketInfo(botConfig);
            BigDecimal amount = BigDecimal.valueOf(assetBalance)
                    .setScale(resolveQuantityDecimals(marketInfo), RoundingMode.DOWN);

            if (amount.signum() <= 0) {
                String errorMessage = MessageFormat.format(
                        "Insufficient {0} balance: {1}. Below the minimum order precision for the market.",
                        asset, assetBalance);
                log.warn(errorMessage);
                saveFailedOrder(botConfig.getBotId(), request.getTicker(), errorMessage);
                return;
            }

            // Get asset price, using Bitvavo's dashed market format (e.g. "BTC-EUR")
            double assetPrice = getAssetPrice(market, botConfig);
            log.info("{} price: {} EUR", asset, assetPrice);

            // Calculate asset worth in EUR
            double assetWorth = amount.doubleValue() * assetPrice;
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
                    .market(market)
                    .side("sell")
                    .orderType("market")
                    .amount(amount)
                    .operatorId(botConfig.getOperatorId())
                    .build();

            CreateOrderResponse orderResponse;
            String orderId;
            String status;

            // Check if this is a dry run
            if (Boolean.TRUE.equals(request.getDryRun())) {
                // Skip sending the order to Bitvavo in dry run mode
                log.info("DRY RUN: Skipping sending sell order to Bitvavo");
                // Generate a dummy order ID for dry run
                orderId = "dry-run-" + System.currentTimeMillis();
                status = "COMPLETED";
                orderResponse = null;
            } else {
                // Send the order to Bitvavo in normal mode
                orderResponse = bitvavoApiClient.post(
                        "/order", orderRequest, CreateOrderResponse.class, botConfig.getApiKey(), botConfig.getApiSecret());
                orderId = orderResponse.getOrderId().toString();
                // Record the status the exchange actually returned (e.g. "new", "filled")
                status = (orderResponse.getStatus() != null && !orderResponse.getStatus().isEmpty())
                        ? orderResponse.getStatus()
                        : "COMPLETED";
                log.info("Sell order placed successfully: {}", orderId);
            }

            // Save order to database
            Instant orderTimestamp = (orderResponse != null && orderResponse.getCreated() != null)
                    ? Instant.ofEpochMilli(orderResponse.getCreated())
                    : Instant.now();

            Order order = Order.builder()
                    .botId(botConfig.getBotId())
                    .orderId(orderId)
                    .ticker(request.getTicker())
                    .timestamp(orderTimestamp)
                    .status(status)
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
    double getEurBalance(BotConfiguration botConfig) {
        return getAssetBalance(botConfig, "EUR");
    }

    /**
     * Get the asset balance for a bot.
     *
     * @param botConfig The bot configuration
     * @param asset     The asset symbol (e.g., "BTC")
     * @return The asset balance
     */
    double getAssetBalance(BotConfiguration botConfig, String asset) {
        GetAccountBalanceResponse[] balanceResponses = bitvavoApiClient.get(
                "/balance?symbol=" + asset, GetAccountBalanceResponse[].class, botConfig.getApiKey(), botConfig.getApiSecret());

        if (balanceResponses == null || balanceResponses.length == 0) {
            return 0.0;
        }

        return balanceResponses[0].getAvailable().doubleValue();
    }

    /**
     * Get the price of an asset.
     * Bitvavo returns a single price object when a market is specified.
     *
     * @param ticker The ticker in Bitvavo's market format (e.g., "BTC-EUR")
     * @return The asset price in EUR
     */
    double getAssetPrice(String ticker, BotConfiguration botConfig) {
        GetPriceResponse priceResponse = bitvavoApiClient.get(
                "/ticker/price?market=" + ticker, GetPriceResponse.class, botConfig.getApiKey(), botConfig.getApiSecret());

        if (priceResponse == null) {
            throw new IllegalStateException("No price returned by Bitvavo for market: " + ticker);
        }

        return priceResponse.getPrice().doubleValue();
    }

    /**
     * Fetch the market information (precision and minimums) for the bot's trading pair.
     *
     * @param botConfig The bot configuration
     * @return The market information, or null if Bitvavo returned none
     */
    private GetMarketResponse getMarketInfo(BotConfiguration botConfig) {
        String market = toBitvavoMarket(botConfig.getTradingPair());
        return bitvavoApiClient.get(
                "/markets?market=" + market, GetMarketResponse.class,
                botConfig.getApiKey(), botConfig.getApiSecret());
    }

    /**
     * Convert a ticker to Bitvavo's market format (e.g. "BTCEUR" or "BTC-EUR" to "BTC-EUR").
     * Bitvavo rejects market parameters without the dash (errorCode 205).
     *
     * @param ticker The ticker or trading pair
     * @return The market in Bitvavo's format
     */
    private String toBitvavoMarket(String ticker) {
        String market = ticker.replace("-", "");
        if (market.endsWith("EUR")) {
            market = market.substring(0, market.length() - "EUR".length()) + "-EUR";
        }
        return market;
    }

    /**
     * Resolve the maximum number of decimals allowed for an order amount.
     *
     * @param market The market information, may be null
     * @return The allowed decimals
     */
    private int resolveQuantityDecimals(GetMarketResponse market) {
        return (market != null && market.getQuantityDecimals() != null)
                ? market.getQuantityDecimals()
                : FALLBACK_QUANTITY_DECIMALS;
    }

    /**
     * Resolve the maximum number of decimals allowed for an order amountQuote.
     *
     * @param market The market information, may be null
     * @return The allowed decimals
     */
    private int resolveNotionalDecimals(GetMarketResponse market) {
        return (market != null && market.getNotionalDecimals() != null)
                ? market.getNotionalDecimals()
                : FALLBACK_NOTIONAL_DECIMALS;
    }

    /**
     * Save a failed order to the database.
     *
     * @param botId        The bot ID
     * @param ticker       The ticker
     * @param errorMessage The error message
     */
    void saveFailedOrder(String botId, String ticker, String errorMessage) {
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
    void updatePosition(String botId, String ticker, String status) {
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
