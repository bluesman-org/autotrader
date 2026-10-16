package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryRequest;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.DailyView;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.DetailsView;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.FiltersView;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.HeaderView;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.Option;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.PagerView;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.SizeOption;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.TotalsView;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.TradeRowView;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds the engine-trades tab: the closed round trips of the selected period with their filter,
 * sort and paging state, the period's totals and the realised P&L by day.
 *
 * <p>The period bounds the database read; filtering, sorting and paging then run in memory because
 * the totals and the daily breakdown cover the whole selected period, not one page. Time presets
 * and the custom range resolve in the display zone ({@code autotrader.ui.time-zone}, the machine's
 * zone by default); anything unparseable falls back to the default period.</p>
 */
@Service
public class TradeHistoryService {

    private static final String DASH = "—";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final List<Integer> PAGE_SIZES = List.of(5, 10, 25, 50);
    private static final String DEFAULT_SORT = "entry";
    private static final List<String> HUES = List.of("hue-0", "hue-1", "hue-2", "hue-3", "hue-4", "hue-5");

    private final LivePositionRepository positions;
    private final LiveOrderRepository orders;
    private final DisplayZone display;
    private final HistoryPeriods periods;
    private final Clock clock;

    @Autowired
    public TradeHistoryService(LivePositionRepository positions, LiveOrderRepository orders,
                               @Value("${autotrader.ui.time-zone:}") String timeZone) {
        this(positions, orders,
                timeZone == null || timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone),
                Clock.systemUTC());
    }

    TradeHistoryService(LivePositionRepository positions, LiveOrderRepository orders, ZoneId zone,
                        Clock clock) {
        this.positions = positions;
        this.orders = orders;
        this.display = new DisplayZone(zone);
        this.periods = new HistoryPeriods(display, clock);
        this.clock = clock;
    }

    /**
     * @return The label of the zone times are shown in, e.g. {@code CEST} or {@code UTC}
     */
    public String zoneLabel() {
        return display.label();
    }

    /**
     * Build the tab's model for the request.
     *
     * @param request The filter, sort and paging state, typically bound from the query string
     * @return The model
     */
    public TradeHistoryView view(TradeHistoryRequest request) {
        HistoryPeriods.Range range = periods.resolve(request.getPeriod(), request.getFromDate(),
                request.getFromTime(), request.getToDate(), request.getToTime());
        // The filter bar's strategy options come from the whole period, so narrowing by one
        // strategy never collapses the list to that strategy.
        List<LivePosition> periodRows = positions
                .findByStatusAndEntryTimeRange("CLOSED", range.fromMillis(), range.toMillis());
        List<LivePosition> filtered = periodRows.stream()
                .filter(position -> matchesStrategy(position, request.getStrategy()))
                .filter(position -> matchesType(position, request.getType()))
                .filter(position -> matchesResult(position, request.getResult()))
                .toList();
        String sortKey = sortKey(request.getSort());
        boolean ascending = "asc".equalsIgnoreCase(request.getDir());
        List<LivePosition> sorted = new ArrayList<>(filtered);
        sorted.sort(comparator(sortKey, ascending));

        int size = pageSize(request.getSize());
        int pages = Math.max((sorted.size() + size - 1) / size, 1);
        int page = clamp(request.getPage() == null ? 1 : request.getPage(), 1, pages);
        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(fromIndex + size, sorted.size());
        List<LivePosition> pageRows = sorted.subList(fromIndex, toIndex);

        Map<String, LiveOrder> refs = orderReferences(pageRows);
        List<TradeRowView> rows = new ArrayList<>();
        for (int i = 0; i < pageRows.size(); i++) {
            rows.add(row(pageRows.get(i), fromIndex + i + 1, refs));
        }
        return new TradeHistoryView(rows, headers(request, sortKey, ascending, size),
                filters(request, periodRows), pager(request, fromIndex, toIndex, sorted.size(), page, pages, size),
                totals(sorted, range), daily(sorted));
    }

    // ---- filtering, sorting, paging ---------------------------------------------------------

    private static boolean matchesStrategy(LivePosition position, String strategy) {
        return !notBlank(strategy) || strategy.equals(position.getStrategyId());
    }

    private static boolean matchesType(LivePosition position, String type) {
        if (!"dry".equalsIgnoreCase(type) && !"real".equalsIgnoreCase(type)) {
            return true;
        }
        return "dry".equalsIgnoreCase(type) ? position.isDryRun() : !position.isDryRun();
    }

    private static boolean matchesResult(LivePosition position, String result) {
        if (!"wins".equalsIgnoreCase(result) && !"losses".equalsIgnoreCase(result)) {
            return true;
        }
        return "wins".equalsIgnoreCase(result) ? profit(position) >= 0 : profit(position) < 0;
    }

    private static String sortKey(String sort) {
        return sort == null || sort.isBlank() ? DEFAULT_SORT : sort;
    }

    private Comparator<LivePosition> comparator(String sort, boolean ascending) {
        Comparator<LivePosition> comparator = switch (sort) {
            case "exit" -> Comparator.comparingLong(position -> position.getExitTime() == null
                    ? 0 : position.getExitTime());
            case "strategy" -> Comparator.comparing(LivePosition::getStrategyId,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            case "type" -> Comparator.comparing(LivePosition::isDryRun);
            case "price" -> Comparator.comparingDouble(LivePosition::getEntryPrice);
            case "quantity" -> Comparator.comparingDouble(LivePosition::getQuantity);
            case "result" -> Comparator.comparingDouble(TradeHistoryService::profit);
            default -> Comparator.comparingLong(LivePosition::getEntryTime);
        };
        Comparator<LivePosition> tieBreak = Comparator.comparingLong(LivePosition::getEntryTime).reversed();
        return (ascending ? comparator : comparator.reversed()).thenComparing(tieBreak);
    }

    private static int pageSize(Integer size) {
        return size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Map<String, LiveOrder> orderReferences(List<LivePosition> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        long min = rows.stream().mapToLong(LivePosition::getEntryTime).min().orElse(0);
        long max = rows.stream().mapToLong(position -> position.getExitTime() == null
                ? position.getEntryTime() : position.getExitTime()).max().orElse(0);
        Map<String, LiveOrder> refs = new HashMap<>();
        for (LiveOrder order : orders.findBySignalOpenTimeRange(min, max)) {
            refs.put(order.getStrategyId() + "|" + order.getSignalOpenTime(), order);
        }
        return refs;
    }

    // ---- the view ---------------------------------------------------------------------------

    private TradeRowView row(LivePosition position, int number, Map<String, LiveOrder> refs) {
        boolean dry = position.isDryRun();
        double result = profit(position);
        String hue = HUES.get(Math.floorMod(Objects.hashCode(position.getStrategyId()), HUES.size()));
        return new TradeRowView(number, shortId(position.getStrategyId()), "schip " + hue, hue,
                dry ? "DRY RUN" : "REAL", dry ? "dry" : "real",
                display.minute(Instant.ofEpochMilli(position.getEntryTime())),
                position.getExitTime() == null ? DASH
                        : display.minute(Instant.ofEpochMilli(position.getExitTime())),
                euro(position.getEntryPrice()) + " → "
                        + (position.getExitPrice() == null ? DASH : euro(position.getExitPrice())),
                String.format(Locale.ENGLISH, "%.8f", position.getQuantity()),
                signed(result), result >= 0 ? "pos" : "neg", details(position, refs));
    }

    private DetailsView details(LivePosition position, Map<String, LiveOrder> refs) {
        String asset = position.getMarket() == null ? "" : position.getMarket().split("-")[0];
        String quantity = String.format(Locale.ENGLISH, "%.8f", position.getQuantity());
        LiveOrder buy = refs.get(position.getStrategyId() + "|" + position.getEntryTime());
        LiveOrder sell = position.getExitTime() == null ? null
                : refs.get(position.getStrategyId() + "|" + position.getExitTime());
        String boughtFee = position.getEntryFee() != null ? euro(position.getEntryFee())
                : buy != null && buy.getFee() != null ? euro(buy.getFee()) : DASH;
        // Only the buy fee is stored on a position; the sell fee comes from the matching order
        // when it is found and is otherwise estimated with the engine's taker rate.
        String soldFee = sell != null && sell.getFee() != null ? euro(sell.getFee())
                : position.getExitPrice() == null ? DASH
                : euro(position.getQuantity() * position.getExitPrice() * BacktestDefaults.FEE_RATE);
        String references = position.isDryRun() ? "none — dry run (simulated)"
                : "buy " + shortRef(buy) + " · sell " + shortRef(sell);
        return new DetailsView(
                display.minute(Instant.ofEpochMilli(position.getEntryTime())) + " · " + quantity + " "
                        + asset + " @ " + euro(position.getEntryPrice()),
                "fee " + boughtFee,
                position.getExitTime() == null ? DASH
                        : display.minute(Instant.ofEpochMilli(position.getExitTime())) + " · " + quantity
                        + " " + asset + " @ " + (position.getExitPrice() == null ? DASH
                        : euro(position.getExitPrice())),
                "fee " + soldFee,
                duration(position.getEntryTime(), position.getExitTime()),
                references);
    }

    private List<HeaderView> headers(TradeHistoryRequest request, String sort, boolean ascending, int size) {
        List<HeaderView> headers = new ArrayList<>();
        headers.add(header("Entry", "left", "entry", request, sort, ascending, size));
        headers.add(header("Exit", "left", "exit", request, sort, ascending, size));
        headers.add(header("Strategy", "left", "strategy", request, sort, ascending, size));
        headers.add(header("Type", "left", "type", request, sort, ascending, size));
        headers.add(header("Price", "", "price", request, sort, ascending, size));
        headers.add(header("Qty", "", "quantity", request, sort, ascending, size));
        headers.add(header("Result", "", "result", request, sort, ascending, size));
        return headers;
    }

    private HeaderView header(String label, String alignClass, String key, TradeHistoryRequest request,
                              String sort, boolean ascending, int size) {
        boolean active = key.equals(sort);
        String direction = active ? (ascending ? "desc" : "asc") : "asc";
        return new HeaderView(label, alignClass, query(request, key, direction, 1, size),
                active, active ? (ascending ? "▲" : "▼") : "");
    }

    private FiltersView filters(TradeHistoryRequest request, List<LivePosition> periodRows) {
        List<Option> strategies = periodRows.stream().map(LivePosition::getStrategyId).distinct().sorted()
                .map(id -> new Option(id, shortId(id))).collect(Collectors.toCollection(ArrayList::new));
        if (notBlank(request.getStrategy())
                && strategies.stream().noneMatch(option -> option.value().equals(request.getStrategy()))) {
            strategies.add(new Option(request.getStrategy(), shortId(request.getStrategy())));
        }
        boolean custom = HistoryPeriods.hasCustomRange(request.getFromDate(), request.getToDate());
        String preset = HistoryPeriods.preset(request.getPeriod());
        return new FiltersView(request.getStrategy(), request.getType(), request.getResult(),
                custom ? "" : preset,
                request.getFromDate(), request.getFromTime(), request.getToDate(), request.getToTime(),
                strategies, HistoryPeriods.OPTIONS);
    }

    private PagerView pager(TradeHistoryRequest request, int fromIndex, int toIndex, int total, int page,
                            int pages, int size) {
        String prev = page > 1 ? query(request, sortKey(request.getSort()), dir(request), page - 1, size) : null;
        String next = page < pages ? query(request, sortKey(request.getSort()), dir(request), page + 1, size) : null;
        List<SizeOption> sizes = PAGE_SIZES.stream()
                .map(candidate -> new SizeOption(String.valueOf(candidate),
                        query(request, sortKey(request.getSort()), dir(request), 1, candidate),
                        candidate == size))
                .toList();
        return new PagerView(total == 0 ? 0 : fromIndex + 1, toIndex, total, page, pages, size,
                prev, next, sizes);
    }

    private TotalsView totals(List<LivePosition> rows, HistoryPeriods.Range range) {
        double total = rows.stream().mapToDouble(TradeHistoryService::profit).sum();
        long wins = rows.stream().filter(position -> profit(position) >= 0).count();
        String winRate = rows.isEmpty() ? DASH : Math.round(100.0 * wins / rows.size()) + "%";
        return new TotalsView(rows.size(), signed(total), total >= 0 ? "pos" : "neg", winRate, range.label());
    }

    private List<DailyView> daily(List<LivePosition> rows) {
        Map<LocalDate, double[]> byDay = new TreeMap<>();
        for (LivePosition position : rows) {
            if (position.isDryRun() || position.getExitTime() == null) {
                continue;
            }
            LocalDate day = Instant.ofEpochMilli(position.getExitTime()).atZone(display.zone()).toLocalDate();
            double[] accumulator = byDay.computeIfAbsent(day, key -> new double[2]);
            accumulator[0] += profit(position);
            accumulator[1] += 1;
        }
        List<DailyView> daily = new ArrayList<>();
        double cumulative = 0;
        for (Map.Entry<LocalDate, double[]> entry : byDay.entrySet()) {
            double dayTotal = entry.getValue()[0];
            cumulative += dayTotal;
            daily.add(new DailyView(display.day(entry.getKey()), (int) entry.getValue()[1],
                    signed(dayTotal), dayTotal >= 0 ? "pos" : "neg",
                    signed(cumulative), cumulative >= 0 ? "pos" : "neg"));
        }
        return daily;
    }

    // ---- links and small helpers ------------------------------------------------------------

    private String query(TradeHistoryRequest request, String sort, String dir, Integer page, Integer size) {
        Map<String, String> params = new LinkedHashMap<>();
        add(params, "strategy", request.getStrategy());
        add(params, "type", request.getType());
        add(params, "result", request.getResult());
        if (!HistoryPeriods.hasCustomRange(request.getFromDate(), request.getToDate())) {
            add(params, "period", request.getPeriod());
        }
        add(params, "fromDate", request.getFromDate());
        add(params, "fromTime", request.getFromTime());
        add(params, "toDate", request.getToDate());
        add(params, "toTime", request.getToTime());
        if (sort != null) {
            params.put("sort", sort);
        }
        if (dir != null) {
            params.put("dir", dir);
        }
        if (size != null) {
            params.put("size", String.valueOf(size));
        }
        if (page != null && page > 1) {
            params.put("page", String.valueOf(page));
        }
        String joined = params.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        return joined.isEmpty() ? "" : "?" + joined;
    }

    private static void add(Map<String, String> params, String key, String value) {
        if (notBlank(value)) {
            params.put(key, value);
        }
    }

    private static String dir(TradeHistoryRequest request) {
        return "asc".equalsIgnoreCase(request.getDir()) ? "asc" : "desc";
    }

    private static double profit(LivePosition position) {
        return position.getProfit() == null ? 0 : position.getProfit();
    }

    private static String duration(long fromMillis, Long toMillis) {
        if (toMillis == null) {
            return DASH;
        }
        long seconds = Math.max((toMillis - fromMillis) / 1000, 0);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + " h " + minutes + " min";
        }
        if (minutes > 0) {
            return minutes + " min " + secs + " s";
        }
        return secs + " s";
    }

    private static String shortId(String strategyId) {
        return strategyId == null ? DASH
                : strategyId.length() <= 8 ? strategyId : strategyId.substring(0, 8);
    }

    private static String shortRef(LiveOrder order) {
        if (order == null || order.getOrderId() == null) {
            return DASH;
        }
        String id = order.getOrderId();
        return id.length() <= 8 ? id : id.substring(0, 8) + "…";
    }

    private static String euro(double value) {
        return ViewText.euro(value);
    }

    private static String signed(double value) {
        return ViewText.signed(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
