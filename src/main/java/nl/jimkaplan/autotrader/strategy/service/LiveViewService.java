package nl.jimkaplan.autotrader.strategy.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.MarketFormat;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.GetBookResponse;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.repository.RoundTripStats;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.controller.LiveCardView;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the live page's model from the engine's own state: each strategy's status, its open
 * position valued at the exchange's current prices, its realised round-trip statistics and its
 * most recent orders.
 *
 * <p>Read-only, and a failing price lookup degrades to dashes instead of breaking the page. Times
 * are shown in the configured display zone ({@code autotrader.ui.time-zone}, the machine's zone by
 * default — the container runs in the user's local time).</p>
 */
@Slf4j
@Service
public class LiveViewService {

    private static final double FEE_RATE = BacktestDefaults.FEE_RATE;
    private static final String DASH = "—";

    private final LiveStrategyService liveStrategyService;
    private final LivePositionRepository livePositionRepository;
    private final LiveOrderRepository liveOrderRepository;
    private final BitvavoApiClient bitvavoApiClient;

    private final DisplayZone display;

    public LiveViewService(LiveStrategyService liveStrategyService,
                           LivePositionRepository livePositionRepository,
                           LiveOrderRepository liveOrderRepository,
                           BitvavoApiClient bitvavoApiClient,
                           @Value("${autotrader.ui.time-zone:}") String timeZone) {
        this.liveStrategyService = liveStrategyService;
        this.livePositionRepository = livePositionRepository;
        this.liveOrderRepository = liveOrderRepository;
        this.bitvavoApiClient = bitvavoApiClient;
        this.display = new DisplayZone(timeZone);
    }

    /**
     * @return The label of the zone times are shown in, e.g. {@code CEST} or {@code UTC}
     */
    public String zoneLabel() {
        return display.label();
    }

    /**
     * @return The zone's IANA id, e.g. {@code Europe/Amsterdam} — the page passes it to its
     *         refresh script so the "last refresh" stamp renders in the same zone and format
     */
    public String zoneId() {
        return display.id();
    }

    /**
     * @return One card per strategy — the running ones first, then the newest — with the prices
     *         that could be read
     */
    public List<LiveCardView> cards() {
        List<LiveStrategy> strategies = liveStrategyService.active();
        Map<String, GetBookResponse> books = books(strategies);
        Instant now = Instant.now();
        return strategies.stream()
                .sorted(Comparator
                        .comparing((LiveStrategy strategy) -> !liveStrategyService.isRunning(strategy.getId()))
                        .thenComparing(LiveStrategy::getCreatedTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(strategy -> card(strategy, books.get(strategy.getMarket()), now))
                .toList();
    }

    private Map<String, GetBookResponse> books(List<LiveStrategy> strategies) {
        Map<String, GetBookResponse> books = new HashMap<>();
        for (String market : strategies.stream().map(LiveStrategy::getMarket).distinct().toList()) {
            try {
                GetBookResponse book = bitvavoApiClient.getPublic(
                        "/ticker/book?market=" + MarketFormat.toMarket(market), GetBookResponse.class);
                if (book != null) {
                    books.put(market, book);
                }
            } catch (RestClientException e) {
                log.warn("Could not read the current {} prices: {}", market, e.getMessage());
            }
        }
        return books;
    }

    private LiveCardView card(LiveStrategy strategy, GetBookResponse book, Instant now) {
        boolean running = liveStrategyService.isRunning(strategy.getId());
        boolean connected = running && liveStrategyService.isConnected(strategy.getId());
        String lastError = running ? liveStrategyService.lastError(strategy.getId()) : null;
        Optional<RoundTripStats> stats = roundTrips(strategy.getId());
        LivePosition position = livePositionRepository
                .findByStrategyIdAndStatus(strategy.getId(), "OPEN").orElse(null);
        String name = strategy.getName() == null || strategy.getName().isBlank()
                ? strategy.getMarket() + " " + strategy.getInterval()
                : strategy.getName();
        List<LiveOrder> recentOrders = liveOrderRepository
                .findTop8ByStrategyIdOrderByCreatedTimeDesc(strategy.getId());
        List<LiveCardView.OrderView> orderViews = orders(recentOrders, position,
                exitResults(strategy.getId(), recentOrders));
        boolean started = strategy.hasStarted() || position != null
                || stats.map(RoundTripStats::getCount).orElse(0L) > 0 || !orderViews.isEmpty();
        return new LiveCardView(strategy.getId(), name, strategy.getMarket(), strategy.getInterval(),
                started, running, connected, strategy.isDryRun(),
                parameters(strategy), statusLine(strategy, running, lastError, now),
                position(position, book, strategy), statsLine(stats), orderViews);
    }

    private String statusLine(LiveStrategy strategy, boolean running, String lastError, Instant now) {
        StringBuilder line = new StringBuilder();
        if (running && strategy.getLastStartedTime() != null) {
            line.append("Running since ").append(display.minute(strategy.getLastStartedTime()))
                    .append(" (").append(age(strategy.getLastStartedTime(), now)).append(')');
        } else if (strategy.getCreatedTime() != null) {
            line.append("Created ").append(display.minute(strategy.getCreatedTime()));
        }
        if (strategy.getLastEvaluatedOpenTime() > 0) {
            Instant evaluated = Instant.ofEpochMilli(strategy.getLastEvaluatedOpenTime());
            line.append(" · Last evaluated ").append(display.second(evaluated));
            if (running) {
                line.append(" (").append(age(evaluated.plusMillis(intervalMillis(strategy)), now)).append(" ago)");
            }
        }
        return line.append(" · Last error: ")
                .append(lastError == null || lastError.isBlank() ? DASH : lastError).toString();
    }

    private String parameters(LiveStrategy strategy) {
        return String.format(Locale.ENGLISH,
                "MACD %d/%d/%d · SAR %s/%s · entry: %s · exit: %s · €%.0f per buy · warm-up %d bars",
                strategy.getMacdFastPeriod(), strategy.getMacdSlowPeriod(), strategy.getMacdSignalPeriod(),
                ViewText.trimmedDecimal(strategy.getSarAcceleration(), 8),
                ViewText.trimmedDecimal(strategy.getSarMaximum(), 8),
                conditions(strategy.getEntryConditions()), conditions(strategy.getExitConditions()),
                strategy.getAmountPerBuy(), strategy.getWarmUpCandles());
    }

    private static String conditions(List<String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return "none";
        }
        return conditions.stream().map(LiveViewService::prettyCondition).collect(Collectors.joining(", "));
    }

    private static String prettyCondition(String name) {
        String lower = name.toLowerCase(Locale.ENGLISH).replace('_', ' ');
        for (String indicator : List.of("macd", "sar")) {
            if (lower.startsWith(indicator)) {
                return indicator.toUpperCase(Locale.ENGLISH) + lower.substring(indicator.length());
            }
        }
        return lower;
    }

    private LiveCardView.PositionView position(LivePosition position, GetBookResponse book,
                                               LiveStrategy strategy) {
        if (position == null) {
            return null;
        }
        String buyText = DASH;
        String sellText = DASH;
        String valueText = DASH;
        String ifClosedText = DASH;
        String ifClosedClass = "";
        if (book != null && book.getBid() != null && book.getAsk() != null) {
            buyText = euro(book.getAsk().doubleValue());
            sellText = euro(book.getBid().doubleValue());
            double proceeds = position.getQuantity() * book.getBid().doubleValue();
            valueText = euro(proceeds);
            double entryCost = position.getQuantity() * position.getEntryPrice()
                    + (position.getEntryFee() == null ? 0 : position.getEntryFee());
            double result = proceeds * (1 - FEE_RATE) - entryCost;
            ifClosedText = signed(result);
            ifClosedClass = result >= 0 ? "pos" : "neg";
        }
        Instant filled = Instant.ofEpochMilli(position.getEntryTime() + intervalMillis(strategy));
        String asset = strategy.getMarket().split("-")[0];
        return new LiveCardView.PositionView(
                String.format(Locale.ENGLISH, "LONG %s %s", ViewText.decimal(position.getQuantity(), 8), asset),
                display.minute(filled) + " · " + euro(position.getEntryPrice()),
                buyText, sellText, valueText, ifClosedText, ifClosedClass,
                position.getEntryFee() == null ? DASH : euro(position.getEntryFee()));
    }

    private Optional<RoundTripStats> roundTrips(String strategyId) {
        return livePositionRepository.aggregateRoundTrips(strategyId).stream().findFirst();
    }

    private String statsLine(Optional<RoundTripStats> aggregate) {
        if (aggregate.isEmpty()) {
            return "No completed round trips yet.";
        }
        RoundTripStats stats = aggregate.get();
        long wins = stats.getWins();
        long losses = stats.getCount() - wins;
        StringBuilder line = new StringBuilder("Realised since start: ")
                .append(stats.getCount()).append(" round trips · ").append(signed(stats.getTotal()))
                .append(" · ").append(wins).append(" wins / ").append(losses).append(" losses");
        if (wins > 0) {
            line.append(" · avg win ").append(signed(stats.getWinTotal() / wins));
        }
        if (losses > 0) {
            line.append(" · avg loss ").append(signed((stats.getTotal() - stats.getWinTotal()) / losses));
        }
        return line.toString();
    }

    private static double profit(LivePosition position) {
        return position.getProfit() == null ? 0 : position.getProfit();
    }

    /**
     * The realised result of each of the recent orders' exit signals. Only the newest eight orders
     * are shown, so only their signal times are looked up: the rest of the strategy's history is
     * not read at all.
     */
    private Map<Long, Double> exitResults(String strategyId, List<LiveOrder> recentOrders) {
        List<Long> signalTimes = recentOrders.stream()
                .map(LiveOrder::getSignalOpenTime)
                .distinct()
                .toList();
        if (signalTimes.isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> resultByExitSignal = new HashMap<>();
        for (LivePosition position : livePositionRepository
                .findAllByStrategyIdAndStatusAndExitTimeIn(strategyId, "CLOSED", signalTimes)) {
            if (position.getExitTime() != null) {
                resultByExitSignal.put(position.getExitTime(), profit(position));
            }
        }
        return resultByExitSignal;
    }

    private List<LiveCardView.OrderView> orders(List<LiveOrder> recentOrders, LivePosition openPosition,
                                                Map<Long, Double> resultByExitSignal) {
        return recentOrders.stream()
                .map(order -> orderView(order, openPosition, resultByExitSignal))
                .toList();
    }

    private LiveCardView.OrderView orderView(LiveOrder order, LivePosition openPosition,
                                             Map<Long, Double> resultByExitSignal) {
        String badge;
        String badgeClass;
        if ("SKIPPED".equals(order.getStatus())) {
            badge = "Skipped — candle unavailable";
            badgeClass = "";
        } else if (order.isDryRun()) {
            badge = "DRY RUN";
            badgeClass = "dry";
        } else if ("FILLED".equals(order.getStatus())) {
            badge = "REAL";
            badgeClass = "real";
        } else {
            badge = order.getStatus();
            badgeClass = "warn";
        }
        Double result = "SELL".equals(order.getSide())
                ? resultByExitSignal.get(order.getSignalOpenTime()) : null;
        boolean opened = "BUY".equals(order.getSide()) && openPosition != null
                && openPosition.getEntryTime() == order.getSignalOpenTime();
        return new LiveCardView.OrderView(
                order.getCreatedTime() == null ? DASH : display.clockText(order.getCreatedTime()),
                order.getSide(),
                order.getQuantity() == null ? DASH : ViewText.decimal(order.getQuantity(), 8),
                order.getPrice() == null ? DASH : euro(order.getPrice()), badge, badgeClass,
                result == null ? DASH : signed(result),
                result == null ? "" : (result >= 0 ? "pos" : "neg"),
                opened);
    }

    private long intervalMillis(LiveStrategy strategy) {
        return CandleInterval.fromCode(strategy.getInterval()).getMillis();
    }

    private static String age(Instant from, Instant to) {
        long seconds = Math.max(Duration.between(from, to).getSeconds(), 0);
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " h";
        }
        return (hours / 24) + " d " + (hours % 24) + " h";
    }

    private static String euro(double value) {
        return ViewText.euro(value);
    }

    private static String signed(double value) {
        return ViewText.signed(value);
    }
}
