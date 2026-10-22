package nl.jimkaplan.autotrader.strategy.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.MarketFormat;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.Fills;
import nl.jimkaplan.autotrader.bitvavo.model.GetBookResponse;
import nl.jimkaplan.autotrader.bitvavo.model.OrderResponse;
import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.controller.ExternalCloseView;
import nl.jimkaplan.autotrader.strategy.controller.LiveCloseResult;
import nl.jimkaplan.autotrader.strategy.controller.LiveCloseView;
import nl.jimkaplan.autotrader.strategy.controller.ReconcileView;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Closes a live position on the operator's instruction, and attaches a manual sell made on the
 * exchange to an open position.
 *
 * <p>The engine's own close reuses {@link LiveTradingService}'s sell path: a real close is a market
 * sell with the same pre-write, client order id, fill handling and reconciliation invariants as a
 * signal exit; a dry close is a simulated sell at the current bid. A manual close is different: the
 * engine did not place the order and has no client order id for it, so the operator picks the
 * exchange sell from the strategy's own history. The app verifies it (market, side, filled status,
 * whole position, fee) and records it as an external fill. It never guesses attribution from a
 * balance or a timestamp — the exchange order is the source of truth and the operator identifies
 * it.</p>
 */
@Slf4j
@Service
public class LiveCloseService {

    static final int ORDER_HISTORY_LIMIT = 1000;
    static final double QUANTITY_TOLERANCE = 1e-9;

    private static final double FEE_RATE = BacktestDefaults.FEE_RATE;
    private static final String DASH = "—";

    private final LivePositionRepository livePositionRepository;
    private final LiveOrderRepository liveOrderRepository;
    private final BitvavoCredentialsService credentialsService;
    private final BitvavoApiClient bitvavoApiClient;
    private final LiveTradingService liveTradingService;
    private final DisplayZone display;

    public LiveCloseService(LivePositionRepository livePositionRepository,
                            LiveOrderRepository liveOrderRepository,
                            BitvavoCredentialsService credentialsService,
                            BitvavoApiClient bitvavoApiClient,
                            LiveTradingService liveTradingService,
                            @Value("${autotrader.ui.time-zone:}") String timeZone) {
        this.livePositionRepository = livePositionRepository;
        this.liveOrderRepository = liveOrderRepository;
        this.credentialsService = credentialsService;
        this.bitvavoApiClient = bitvavoApiClient;
        this.liveTradingService = liveTradingService;
        this.display = new DisplayZone(timeZone);
    }

    /**
     * @param strategy The stopped strategy
     * @param position The open position
     * @return The confirmation view, with a current bid when one could be read
     */
    public LiveCloseView preview(LiveStrategy strategy, LivePosition position) {
        GetBookResponse book = book(strategy.getMarket());
        String quantityText = quantityText(strategy, position);
        String entryText = display.minuteYear(Instant.ofEpochMilli(
                position.getEntryTime() + intervalMillis(strategy))) + " · " + euro(position.getEntryPrice());
        String entryFeeText = position.getEntryFee() == null ? DASH : euro(position.getEntryFee());
        String warning = strategy.isDryRun()
                ? "This records a simulated sell at the current bid; no exchange order is sent."
                : "This places a real market sell of the position at market. The fill price may "
                + "differ from this estimate; the position closes when the exchange reports it filled.";
        if (book == null || book.getBid() == null) {
            return new LiveCloseView(strategy.getId(), name(strategy), strategy.getMarket(),
                    strategy.getInterval(), strategy.isDryRun(), false, quantityText, entryText,
                    entryFeeText, DASH, DASH, DASH, DASH, "",
                    "The current price is unavailable, so the close cannot run now. Try again in a moment.");
        }
        double bid = book.getBid().doubleValue();
        double proceeds = position.getQuantity() * bid;
        double fee = proceeds * FEE_RATE;
        double profit = TradeMath.closeProfit(proceeds, position.getQuantity(),
                position.getEntryPrice(), position.getEntryFee(), fee);
        return new LiveCloseView(strategy.getId(), name(strategy), strategy.getMarket(),
                strategy.getInterval(), strategy.isDryRun(), true, quantityText, entryText,
                entryFeeText, euro(bid), euro(proceeds), euro(fee), signed(profit),
                profit >= 0 ? "pos" : "neg", warning);
    }

    /**
     * Close the position. A dry close is immediate; a real close is a market sell and leaves the
     * position open unless the exchange reports it filled.
     *
     * @param strategy The stopped strategy
     * @param position The open position
     * @return What happened, for the page
     */
    public LiveCloseResult close(LiveStrategy strategy, LivePosition position) {
        GetBookResponse book = book(strategy.getMarket());
        if (book == null || book.getBid() == null) {
            throw new IllegalArgumentException(
                    "The current price is unavailable, so the close was not executed; try again in a moment.");
        }
        LiveOrder order = liveTradingService.closeManually(strategy, position,
                Instant.now().toEpochMilli(), "manual close in the app", book.getBid().doubleValue());
        boolean closed = "CLOSED".equals(position.getStatus());
        return new LiveCloseResult(closed, order.getStatus(), order.getOrderId(), message(order, closed));
    }

    /**
     * @param strategy The stopped strategy
     * @param position The open position
     * @return The position and the exchange sells that can be attached to it
     */
    public ReconcileView reconcileView(LiveStrategy strategy, LivePosition position) {
        Candidates candidates = candidates(strategy, position);
        return new ReconcileView(strategy.getId(), name(strategy), strategy.getMarket(),
                strategy.getInterval(), strategy.isDryRun(), quantityText(strategy, position),
                display.minuteYear(Instant.ofEpochMilli(
                        position.getEntryTime() + intervalMillis(strategy))) + " · "
                        + euro(position.getEntryPrice()),
                position.getEntryFee() == null ? DASH : euro(position.getEntryFee()),
                candidates.views(), candidates.note());
    }

    /**
     * Attach a filled exchange sell to the open position, close the position at the exchange's
     * average fill and record the sell as an external fill.
     *
     * @param strategy The stopped strategy
     * @param position The open position
     * @param orderId  The exchange order id the operator picked
     * @return The external row
     * @throws IllegalArgumentException when the order does not exist, does not cover the position,
     *                                  is already attached, or is missing the figures needed
     */
    public LiveOrder reconcile(LiveStrategy strategy, LivePosition position, String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Choose the exchange sell that closed the position.");
        }
        if (liveOrderRepository.existsByOrderId(orderId)) {
            throw new IllegalArgumentException("That exchange sell is already attached to a live order.");
        }
        OrderResponse order = find(strategy, position, orderId);
        if (order == null) {
            throw new IllegalArgumentException(
                    "That sell was not found in the exchange's order history; refresh the page and try again.");
        }
        if (!isExternalSell(order)) {
            throw new IllegalArgumentException(
                    "That order is not a filled sell made outside the engine; it cannot be attached here.");
        }
        BigDecimal filledAmount = order.getFilledAmount();
        BigDecimal filledQuote = order.getFilledAmountQuote();
        if (filledAmount == null || filledAmount.signum() <= 0 || filledQuote == null) {
            throw new IllegalArgumentException(
                    "The exchange did not report the sold quantity or proceeds; try again in a moment.");
        }
        if (order.getFeePaid() == null) {
            throw new IllegalArgumentException(
                    "The exchange did not report the sell's fee yet; try again in a moment.");
        }
        Double averagePrice = TradeMath.price(filledAmount, filledQuote);
        if (averagePrice <= 0) {
            throw new IllegalArgumentException(
                    "The exchange did not report a usable fill price for that sell; try again later.");
        }
        if (!covers(filledAmount, position.getQuantity())) {
            throw new IllegalArgumentException("That sell covers only "
                    + ViewText.decimal(filledAmount.doubleValue(), 8) + " of the position's "
                    + ViewText.decimal(position.getQuantity(), 8)
                    + "; sell the remainder first or pick a sell that covers the whole position.");
        }
        Instant filledAt = fillTime(order);
        if (filledAt == null) {
            throw new IllegalArgumentException(
                    "The exchange did not report when that sell filled; try again in a moment.");
        }

        BigDecimal positionQuantity = BigDecimal.valueOf(position.getQuantity());
        BigDecimal feeShare = order.getFeePaid().multiply(positionQuantity)
                .divide(filledAmount, 8, RoundingMode.HALF_UP);
        double proceeds = position.getQuantity() * averagePrice;
        double profit = TradeMath.closeProfit(proceeds, position.getQuantity(),
                position.getEntryPrice(), position.getEntryFee(), feeShare.doubleValue());

        LiveOrder row = LiveOrder.builder()
                .strategyId(strategy.getId())
                .market(strategy.getMarket())
                .side("SELL")
                .status("FILLED")
                .dryRun(false)
                .external(true)
                .signalOpenTime(filledAt.toEpochMilli())
                .reason("manual close outside the engine (operator-reconciled)")
                .amountQuote(proceeds)
                .quantity(position.getQuantity())
                .price(averagePrice)
                .fee(feeShare.doubleValue())
                .orderId(String.valueOf(order.getOrderId()))
                .createdTime(Instant.now())
                .build();
        liveOrderRepository.save(row);

        position.setStatus("CLOSED");
        position.setExitPrice(averagePrice);
        position.setExitTime(filledAt.toEpochMilli());
        position.setProfit(profit);
        livePositionRepository.save(position);
        log.info("Reconciled external sell {} for strategy {}: profit {} EUR",
                order.getOrderId(), strategy.getId(), profit);
        return row;
    }

    private Candidates candidates(LiveStrategy strategy, LivePosition position) {
        OrderResponse[] history = history(strategy, position);
        List<ExternalCloseView> views = new ArrayList<>();
        List<OrderResponse> orders = new ArrayList<>();
        for (OrderResponse order : history) {
            if (order != null) {
                orders.add(order);
            }
        }
        orders.sort(Comparator.comparing(LiveCloseService::fillTime,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        for (OrderResponse order : orders) {
            if (!isExternalSell(order) || order.getOrderId() == null
                    || liveOrderRepository.existsByOrderId(String.valueOf(order.getOrderId()))) {
                continue;
            }
            BigDecimal filledAmount = order.getFilledAmount();
            BigDecimal filledQuote = order.getFilledAmountQuote();
            Double averagePrice = TradeMath.price(filledAmount, filledQuote);
            Instant filledAt = fillTime(order);
            if (filledAmount == null || filledAmount.signum() <= 0 || filledQuote == null
                    || averagePrice == null || filledAt == null || order.getFeePaid() == null) {
                continue;
            }
            boolean covers = covers(filledAmount, position.getQuantity());
            views.add(new ExternalCloseView(String.valueOf(order.getOrderId()),
                    display.minuteYear(filledAt) + " (" + display.label() + ")",
                    ViewText.decimal(filledAmount.doubleValue(), 8), euro(averagePrice),
                    euro(order.getFeePaid().doubleValue()),
                    euro(filledQuote.doubleValue()), covers,
                    covers ? "covers the position" : "covers only "
                            + ViewText.decimal(filledAmount.doubleValue(), 8) + " of "
                            + ViewText.decimal(position.getQuantity(), 8)));
        }
        String note = views.isEmpty()
                ? "No filled sell without a client order id was found for this market since the position opened."
                : null;
        if (history.length == ORDER_HISTORY_LIMIT) {
            note = (note == null ? "" : note + " ")
                    + "The exchange returned a full page of orders; older sells may be missing.";
        }
        return new Candidates(views, note);
    }

    private OrderResponse find(LiveStrategy strategy, LivePosition position, String orderId) {
        for (OrderResponse order : history(strategy, position)) {
            if (order != null && order.getOrderId() != null
                    && orderId.equals(String.valueOf(order.getOrderId()))) {
                return order;
            }
        }
        return null;
    }

    private OrderResponse[] history(LiveStrategy strategy, LivePosition position) {
        BitvavoCredentials credentials = credentialsService.get().orElseThrow(() ->
                new IllegalArgumentException(
                        "No Bitvavo API key is configured; store it on the settings page."));
        OrderResponse[] history = bitvavoApiClient.get(
                "/orders?market=" + strategy.getMarket()
                        + "&start=" + position.getEntryTime()
                        + "&limit=" + ORDER_HISTORY_LIMIT,
                OrderResponse[].class, credentials.getApiKey(), credentials.getApiSecret());
        if (history == null) {
            throw new IllegalArgumentException(
                    "The exchange's order history is unavailable; try again in a moment.");
        }
        return history;
    }

    private GetBookResponse book(String market) {
        try {
            return bitvavoApiClient.getPublic(
                    "/ticker/book?market=" + MarketFormat.toMarket(market), GetBookResponse.class);
        } catch (RestClientException ex) {
            log.warn("Could not read the current {} price for a close: {}", market, ex.getMessage());
            return null;
        }
    }

    private static boolean isExternalSell(OrderResponse order) {
        return order != null
                && "sell".equalsIgnoreCase(order.getSide())
                && "filled".equalsIgnoreCase(order.getStatus())
                && order.getClientOrderId() == null;
    }

    private static boolean covers(BigDecimal filledAmount, double positionQuantity) {
        BigDecimal required = BigDecimal.valueOf(positionQuantity)
                .multiply(BigDecimal.valueOf(1 - QUANTITY_TOLERANCE));
        return filledAmount.compareTo(required) >= 0;
    }

    private static Instant fillTime(OrderResponse order) {
        Instant latest = null;
        if (order.getFills() != null) {
            for (Fills fill : order.getFills()) {
                if (fill.getTimestamp() != null
                        && (latest == null || fill.getTimestamp().isAfter(latest))) {
                    latest = fill.getTimestamp();
                }
            }
        }
        if (latest != null) {
            return latest;
        }
        return order.getCreated() == null ? null : Instant.ofEpochMilli(order.getCreated());
    }

    private static String message(LiveOrder order, boolean closed) {
        if (closed) {
            String at = order.getPrice() == null ? "" : " at " + euro(order.getPrice());
            String reference = order.getOrderId() == null ? "" : " (order " + order.getOrderId() + ")";
            return "The position was closed" + at + reference + ".";
        }
        if ("REJECTED".equals(order.getStatus())) {
            return "The close was rejected: " + reason(order) + " The position stays open.";
        }
        if ("FAILED".equals(order.getStatus())) {
            return "The close was not settled: " + reason(order)
                    + " The position stays open and the strategy stands down until reconciliation.";
        }
        return "The sell was sent but is not filled yet. The position stays open and the strategy "
                + "stands down until reconciliation.";
    }

    private static String reason(LiveOrder order) {
        return order.getErrorMessage() == null || order.getErrorMessage().isBlank()
                ? "the exchange did not give a reason." : order.getErrorMessage();
    }

    private String quantityText(LiveStrategy strategy, LivePosition position) {
        String asset = strategy.getMarket().split("-")[0];
        return "LONG " + ViewText.decimal(position.getQuantity(), 8) + " " + asset;
    }

    private static long intervalMillis(LiveStrategy strategy) {
        return CandleInterval.fromCode(strategy.getInterval()).getMillis();
    }

    private static String name(LiveStrategy strategy) {
        return strategy.getName() == null || strategy.getName().isBlank()
                ? strategy.getMarket() + " " + strategy.getInterval()
                : strategy.getName();
    }

    private static String euro(double value) {
        return ViewText.euro(value);
    }

    private static String signed(double value) {
        return ViewText.signed(value);
    }

    private record Candidates(List<ExternalCloseView> views, String note) {
    }
}
