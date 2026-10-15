package nl.jimkaplan.autotrader.strategy.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.MarketFormat;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.GetBookResponse;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private final ZoneId zone;
    private final DateTimeFormatter minuteFormat;
    private final DateTimeFormatter secondFormat;
    private final DateTimeFormatter clockFormat;

    public LiveViewService(LiveStrategyService liveStrategyService,
                           LivePositionRepository livePositionRepository,
                           LiveOrderRepository liveOrderRepository,
                           BitvavoApiClient bitvavoApiClient,
                           @Value("${autotrader.ui.time-zone:}") String timeZone) {
        this.liveStrategyService = liveStrategyService;
        this.livePositionRepository = livePositionRepository;
        this.liveOrderRepository = liveOrderRepository;
        this.bitvavoApiClient = bitvavoApiClient;
        this.zone = timeZone == null || timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone);
        this.minuteFormat = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(zone);
        this.secondFormat = DateTimeFormatter.ofPattern("d MMM HH:mm:ss", Locale.ENGLISH).withZone(zone);
        this.clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(zone);
    }

    /**
     * @return The label of the zone times are shown in, e.g. {@code CEST} or {@code UTC}
     */
    public String zoneLabel() {
        return zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /**
     * @return One card per strategy — the running ones first, then the newest — with the prices
     *         that could be read
     */
    public List<LiveCardView> cards() {
        List<LiveStrategy> strategies = liveStrategyService.all();
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
        List<LivePosition> closed = livePositionRepository
                .findAllByStrategyIdAndStatus(strategy.getId(), "CLOSED");
        LivePosition position = livePositionRepository
                .findByStrategyIdAndStatus(strategy.getId(), "OPEN").orElse(null);
        return new LiveCardView(strategy.getId(), strategy.getMarket(), strategy.getInterval(),
                running, connected, strategy.isDryRun(),
                parameters(strategy), statusLine(strategy, running, lastError, now),
                position(position, book, strategy), statsLine(closed),
                orders(strategy.getId(), position, closed));
    }

    private String statusLine(LiveStrategy strategy, boolean running, String lastError, Instant now) {
        StringBuilder line = new StringBuilder();
        if (running && strategy.getLastStartedTime() != null) {
            line.append("Running since ").append(minuteFormat.format(strategy.getLastStartedTime()))
                    .append(" (").append(age(strategy.getLastStartedTime(), now)).append(')');
        } else if (strategy.getCreatedTime() != null) {
            line.append("Created ").append(minuteFormat.format(strategy.getCreatedTime()));
        }
        if (strategy.getLastEvaluatedOpenTime() > 0) {
            Instant evaluated = Instant.ofEpochMilli(strategy.getLastEvaluatedOpenTime());
            line.append(" · Last evaluated ").append(secondFormat.format(evaluated));
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
                String.valueOf(strategy.getSarAcceleration()), String.valueOf(strategy.getSarMaximum()),
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
                String.format(Locale.ENGLISH, "LONG %.8f %s", position.getQuantity(), asset),
                minuteFormat.format(filled) + " · " + euro(position.getEntryPrice()),
                buyText, sellText, valueText, ifClosedText, ifClosedClass,
                position.getEntryFee() == null ? DASH : euro(position.getEntryFee()));
    }

    private String statsLine(List<LivePosition> closed) {
        // The whole round-trip history is walked in memory here, four passes over one document per
        // round trip. Fine while it is tens of rows — a 1m strategy does tens per day — but the
        // read grows with the strategy's age; the Mongo-side aggregation that keeps it flat is
        // PLAN item 21.
        if (closed.isEmpty()) {
            return "No completed round trips yet.";
        }
        double total = closed.stream().mapToDouble(LiveViewService::profit).sum();
        long wins = closed.stream().filter(position -> profit(position) >= 0).count();
        long losses = closed.size() - wins;
        StringBuilder line = new StringBuilder("Realised since start: ")
                .append(closed.size()).append(" round trips · ").append(signed(total));
        if (wins > 0 || losses > 0) {
            line.append(" · ").append(wins).append(" wins / ").append(losses).append(" losses");
            if (wins > 0) {
                line.append(" · avg win ").append(signed(closed.stream()
                        .filter(position -> profit(position) >= 0).mapToDouble(LiveViewService::profit)
                        .average().orElse(0)));
            }
            if (losses > 0) {
                line.append(" · avg loss ").append(signed(closed.stream()
                        .filter(position -> profit(position) < 0).mapToDouble(LiveViewService::profit)
                        .average().orElse(0)));
            }
        }
        return line.toString();
    }

    private static double profit(LivePosition position) {
        return position.getProfit() == null ? 0 : position.getProfit();
    }

    private List<LiveCardView.OrderView> orders(String strategyId, LivePosition openPosition,
                                                List<LivePosition> closed) {
        Map<Long, Double> resultByExitSignal = new HashMap<>();
        for (LivePosition position : closed) {
            if (position.getExitTime() != null) {
                resultByExitSignal.put(position.getExitTime(), profit(position));
            }
        }
        return liveOrderRepository.findTop8ByStrategyIdOrderByCreatedTimeDesc(strategyId).stream()
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
                order.getCreatedTime() == null ? DASH : clockText(order.getCreatedTime()),
                order.getSide(),
                order.getQuantity() == null ? DASH : String.format(Locale.ENGLISH, "%.8f", order.getQuantity()),
                order.getPrice() == null ? DASH : euro(order.getPrice()), badge, badgeClass,
                result == null ? DASH : signed(result),
                result == null ? "" : (result >= 0 ? "pos" : "neg"),
                opened);
    }

    private String clockText(Instant when) {
        return when.atZone(zone).toLocalDate().equals(LocalDate.now(zone))
                ? clockFormat.format(when) : minuteFormat.format(when);
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
        return "€" + String.format(Locale.US, "%,.2f", Math.abs(value));
    }

    private static String signed(double value) {
        return (value < 0 ? "−" : "+") + euro(value);
    }
}
