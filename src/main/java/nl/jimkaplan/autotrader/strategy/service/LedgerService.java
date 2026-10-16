package nl.jimkaplan.autotrader.strategy.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.MarketFormat;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.Fills;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountBalanceResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountHistoryResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetBookResponse;
import nl.jimkaplan.autotrader.bitvavo.model.OrderResponse;
import nl.jimkaplan.autotrader.bitvavo.model.Transaction;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import nl.jimkaplan.autotrader.strategy.controller.LedgerRequest;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.FillRowView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.FiltersView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.OrderRowView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.PriceView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.RawRowView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.TotalsView;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.Option;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.PagerView;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.SizeOption;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds the Bitvavo-ledger tab: the account the engine trades on, read through the bot's API
 * credentials — its orders for the selected market and period with their fills, the raw
 * transactions behind them, the current prices and the period's totals.
 *
 * <p>The ledger is the real account, not the engine's own records, so everything here is read from
 * Bitvavo: {@code GET /orders} for the orders, {@code GET /account/history} for the fills behind
 * the P&amp;L and for the raw view, {@code GET /balance} for the balance the "Balance after" column
 * counts down from, and the public {@code /ticker/book} for the current prices.</p>
 *
 * <p>The P&amp;L is derived at average cost over the market's whole buy/sell history, not just the
 * period: a sell within the period needs to know what its inventory cost, and that happened
 * earlier. The walk ignores deposits, so a sell of coins that were deposited rather than bought
 * shows without a result. A failing read leaves the page with a message instead of data; a failing
 * price lookup degrades to dashes.</p>
 */
@Slf4j
@Service
public class LedgerService {

    private static final String DASH = "—";
    private static final String DEFAULT_MARKET = "BTC-EUR";
    private static final String EUR = "EUR";
    private static final String DEFAULT_VIEW = "orders";
    private static final String RAW_VIEW = "raw";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final List<Integer> PAGE_SIZES = List.of(5, 10, 25, 50);
    private static final List<String> ORDER_TYPES = List.of("market", "limit", "stopLoss",
            "stopLossLimit", "takeProfit", "takeProfitLimit");
    private static final int ORDER_LIMIT = 1000;
    private static final int HISTORY_PAGE_SIZE = 200;
    private static final int MAX_HISTORY_PAGES = 25;

    /**
     * The cost walk starts here: every buy and sell of the account, per market, is needed to know
     * what inventory cost, and the account did not exist before Bitvavo did.
     */
    private static final long GENESIS_MILLIS = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();

    private final BitvavoApiClient bitvavoApiClient;
    private final BotConfigurationService botConfigurationService;
    private final LiveStrategyService liveStrategyService;
    private final DisplayZone display;
    private final HistoryPeriods periods;
    private final Clock clock;

    @Autowired
    public LedgerService(BitvavoApiClient bitvavoApiClient,
                         BotConfigurationService botConfigurationService,
                         LiveStrategyService liveStrategyService,
                         @Value("${autotrader.ui.time-zone:}") String timeZone) {
        this(bitvavoApiClient, botConfigurationService, liveStrategyService, new DisplayZone(timeZone),
                Clock.systemUTC());
    }

    LedgerService(BitvavoApiClient bitvavoApiClient, BotConfigurationService botConfigurationService,
                  LiveStrategyService liveStrategyService, DisplayZone display, Clock clock) {
        this.bitvavoApiClient = bitvavoApiClient;
        this.botConfigurationService = botConfigurationService;
        this.liveStrategyService = liveStrategyService;
        this.display = display;
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
     * @param request The filter, view and paging state, typically bound from the query string
     * @return The model; a message instead of data when the account is not configured or unreadable
     */
    public LedgerView view(LedgerRequest request) {
        HistoryPeriods.Range range = periods.resolve(request.getPeriod(), request.getFromDate(),
                request.getFromTime(), request.getToDate(), request.getToTime());
        String market = market(request);
        boolean raw = RAW_VIEW.equalsIgnoreCase(request.getView());
        GetBookResponse book = book(market);
        PriceView price = price(market, book);
        FiltersView filters = filters(request, market, raw);
        Optional<BotConfiguration> account = account();
        if (account.isEmpty()) {
            return new LedgerView(
                    "No Bitvavo API key is configured, so the account cannot be read. The ledger "
                            + "uses the same key the engine trades with — configure it once with "
                            + "POST /api/bots/create.",
                    price, filters, null, List.of(), pager(request, 0, 1, 1), raw, List.of(),
                    pager(request, 0, 1, 1));
        }
        BotConfiguration bot = account.get();
        try {
            return ledger(request, range, market, raw, book, price, filters, bot);
        } catch (RestClientException e) {
            log.warn("Could not read the Bitvavo ledger for {}: {}", market, e.getMessage());
            return new LedgerView("Could not read the Bitvavo account: " + e.getMessage(), price,
                    filters, null, List.of(), pager(request, 0, 1, 1), raw, List.of(),
                    pager(request, 0, 1, 1));
        }
    }

    private LedgerView ledger(LedgerRequest request, HistoryPeriods.Range range, String market,
                              boolean raw, GetBookResponse book, PriceView price, FiltersView filters,
                              BotConfiguration bot) {
        List<OrderResponse> periodOrders = orders(bot, market, range);
        List<Transaction> marketFills = marketFills(bot, market, clock.instant().toEpochMilli());
        Balance balance = balance(bot, market, marketFills);
        double currentBid = book == null || book.getBid() == null ? Double.NaN : book.getBid().doubleValue();
        Basis basis = Basis.walk(marketFills, quote(market), base(market));

        List<OrderResponse> filtered = periodOrders.stream()
                .filter(order -> matchesSide(order, request.getSide()))
                .filter(order -> matchesType(order, request.getType()))
                .filter(order -> matchesStatus(order, request.getStatus()))
                .sorted(Comparator.comparingLong(LedgerService::created).reversed())
                .toList();

        int pageSize = pageSize(request.getSize());
        int pages = pages(filtered.size(), pageSize);
        int page = Math.min(clamp(request.getPage()), pages);
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, filtered.size());
        List<OrderRowView> rows = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            rows.add(row(filtered.get(i), i + 1, basis, currentBid, balance, market));
        }

        List<Transaction> rawEntries = raw ? history(bot, null, range.fromMillis(), range.toMillis())
                : List.of();
        int rawPages = pages(rawEntries.size(), pageSize);
        int rawPage = Math.min(clamp(request.getPage()), rawPages);
        List<RawRowView> rawRows = raw ? rawRows(rawEntries, rawPage, pageSize) : List.of();

        return new LedgerView(null, price, filters,
                totals(filtered, basis, currentBid, balance.current(), market, range),
                rows, pager(request, filtered.size(), page, pages), raw,
                rawRows, pager(request, rawEntries.size(), rawPage, rawPages));
    }

    // ---- the account ------------------------------------------------------------------------

    /**
     * The account the engine trades on: the key stored for the engine's strategy when it names one,
     * the newest stored key otherwise. The app keeps a single Bitvavo API key for the account, so
     * both paths lead to the same record.
     */
    private Optional<BotConfiguration> account() {
        Optional<BotConfiguration> fromEngine = liveStrategyService.all().stream()
                .filter(strategy -> notBlank(strategy.getBotId()))
                .max(Comparator.comparing(LiveStrategy::getCreatedTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .flatMap(strategy -> botConfigurationService.getBotConfiguration(strategy.getBotId()));
        return fromEngine.or(() -> botConfigurationService.getAllBotConfigurations().stream()
                .max(Comparator.comparing(BotConfiguration::getBotId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))));
    }

    private String market(LedgerRequest request) {
        if (notBlank(request.getMarket())) {
            try {
                // Markets end up in the signed API path and in every generated link, so only a
                // well-formed one is accepted; anything else falls back to the engine's market.
                return MarketFormat.toMarket(request.getMarket());
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring an unsupported market filter: {}", request.getMarket());
            }
        }
        return liveStrategyService.all().stream()
                .max(Comparator.comparing(LiveStrategy::getCreatedTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(LiveStrategy::getMarket)
                .filter(Objects::nonNull)
                .orElse(DEFAULT_MARKET);
    }

    private List<Option> marketOptions(String market) {
        List<String> known = liveStrategyService.all().stream()
                .map(LiveStrategy::getMarket)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        if (!known.contains(market)) {
            known.add(market);
        }
        return known.stream().map(value -> new Option(value, value)).toList();
    }

    // ---- reads ------------------------------------------------------------------------------

    private List<OrderResponse> orders(BotConfiguration bot, String market, HistoryPeriods.Range range) {
        String endpoint = UriComponentsBuilder.fromPath("/orders")
                .queryParam("market", market)
                .queryParam("start", range.fromMillis())
                .queryParam("end", range.toMillis())
                .queryParam("limit", ORDER_LIMIT)
                .build().encode().toUriString();
        OrderResponse[] response = bitvavoApiClient.get(endpoint, OrderResponse[].class,
                bot.getApiKey(), bot.getApiSecret());
        return response == null ? List.of() : List.of(response);
    }

    /**
     * Every buy and sell of the market, oldest first. The cost walk must see what happened before
     * the period — a sell's inventory was bought earlier — so this reads from the account's start,
     * not from the period's.
     */
    private List<Transaction> marketFills(BotConfiguration bot, String market, long toMillis) {
        String base = base(market);
        List<Transaction> fills = new ArrayList<>();
        for (String type : List.of("buy", "sell")) {
            for (Transaction entry : history(bot, type, GENESIS_MILLIS, toMillis)) {
                if (base.equals(entry.getReceivedCurrency()) || base.equals(entry.getSentCurrency())) {
                    fills.add(entry);
                }
            }
        }
        fills.sort(Comparator.comparing(Transaction::getExecutedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return fills;
    }

    private List<Transaction> history(BotConfiguration bot, String type, long fromMillis, long toMillis) {
        List<Transaction> entries = new ArrayList<>();
        for (int page = 1; page <= MAX_HISTORY_PAGES; page++) {
            UriComponentsBuilder endpoint = UriComponentsBuilder.fromPath("/account/history")
                    .queryParam("fromDate", fromMillis)
                    .queryParam("toDate", toMillis)
                    .queryParam("page", page)
                    .queryParam("maxItems", HISTORY_PAGE_SIZE);
            if (type != null) {
                endpoint.queryParam("type", type);
            }
            GetAccountHistoryResponse response = bitvavoApiClient.get(endpoint.build().encode().toUriString(),
                    GetAccountHistoryResponse.class, bot.getApiKey(), bot.getApiSecret());
            if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                break;
            }
            entries.addAll(response.getItems());
            if (response.getTotalPages() == null || page >= response.getTotalPages()) {
                break;
            }
        }
        return entries;
    }

    /**
     * The account's current balance of the market's base currency, plus a way to ask what it was
     * after a given order: the walk counts fills backwards from today's balance, so "Balance after"
     * counts only filled orders.
     */
    private Balance balance(BotConfiguration bot, String market, List<Transaction> marketFills) {
        String endpoint = UriComponentsBuilder.fromPath("/balance")
                .queryParam("symbol", base(market))
                .build().encode().toUriString();
        GetAccountBalanceResponse[] balances = bitvavoApiClient.get(endpoint,
                GetAccountBalanceResponse[].class, bot.getApiKey(), bot.getApiSecret());
        String base = base(market);
        double current = 0;
        if (balances != null) {
            for (GetAccountBalanceResponse entry : balances) {
                if (base.equals(entry.getSymbol())) {
                    current = number(entry.getAvailable()) + number(entry.getInOrder());
                }
            }
        }
        NavigableMap<Long, Double> afterByFill = new TreeMap<>();
        double running = current - marketFills.stream().mapToDouble(fill -> baseDelta(fill, base)).sum();
        for (Transaction fill : marketFills) {
            running += baseDelta(fill, base);
            if (fill.getExecutedAt() != null) {
                afterByFill.put(fill.getExecutedAt().toEpochMilli(), running);
            }
        }
        return new Balance(current, afterByFill);
    }

    private PriceView price(String market, GetBookResponse book) {
        if (book == null) {
            return new PriceView(market, DASH, DASH);
        }
        return new PriceView(market, book.getAsk() == null ? DASH : euro(book.getAsk().doubleValue()),
                book.getBid() == null ? DASH : euro(book.getBid().doubleValue()));
    }

    private GetBookResponse book(String market) {
        try {
            return bitvavoApiClient.getPublic("/ticker/book?market=" + market, GetBookResponse.class);
        } catch (RestClientException e) {
            log.warn("Could not read the current {} prices: {}", market, e.getMessage());
            return null;
        }
    }

    // ---- the view ---------------------------------------------------------------------------

    private OrderRowView row(OrderResponse order, int number, Basis basis, double currentBid,
                             Balance balance, String market) {
        boolean buy = isBuy(order);
        double amount = number(order.getFilledAmount());
        double quoteAmount = number(order.getFilledAmountQuote());
        double fee = orderFee(order, market);
        double result = result(order, basis, currentBid);
        String avgPrice = amount > 0 ? euro(quoteAmount / amount)
                : order.getPrice() == null ? DASH : euro(order.getPrice().doubleValue());
        double netCash = buy ? -(quoteAmount + fee) : quoteAmount - fee;
        return new OrderRowView(number,
                order.getCreated() == null ? DASH : display.minute(Instant.ofEpochMilli(order.getCreated())),
                buy ? "BUY" : "SELL", buy ? "side-buy" : "side-sell",
                order.getOrderType() == null ? DASH : order.getOrderType(),
                order.getMarket() == null ? market : order.getMarket(),
                order.getAmount() == null ? amountText(order.getAmountQuote(), market) : amount8(number(order.getAmount())),
                avgPrice, statusLabel(order.getStatus()), statusClass(order.getStatus()),
                balance.after(order), amount > 0 ? euro(fee) : DASH,
                amount > 0 || quoteAmount > 0 ? signed(netCash) : DASH,
                amount > 0 || quoteAmount > 0 ? signClass(netCash) : "",
                order.getFills() == null ? 0 : order.getFills().size(),
                Double.isNaN(result) ? DASH : signed(result),
                Double.isNaN(result) ? "" : signClass(result),
                fills(order, market), filledText(order, market));
    }

    private List<FillRowView> fills(OrderResponse order, String market) {
        if (order.getFills() == null) {
            return List.of();
        }
        List<FillRowView> rows = new ArrayList<>();
        for (Fills fill : order.getFills()) {
            double amount = number(fill.getAmount());
            double price = number(fill.getPrice());
            double fee = quoteFee(fill, market);
            double netCash = isBuy(order) ? -(amount * price + fee) : amount * price - fee;
            rows.add(new FillRowView(
                    fill.getTimestamp() == null ? DASH : display.clockSecond(fill.getTimestamp()),
                    amount8(amount), euro(price), euro4(fee),
                    Boolean.TRUE.equals(fill.getTaker()) ? "taker" : "maker",
                    signed(netCash), signClass(netCash)));
        }
        return rows;
    }

    private String filledText(OrderResponse order, String market) {
        if (order.getFilledAmount() == null || order.getFilledAmount().signum() <= 0
                || order.getAmount() == null) {
            return "";
        }
        BigDecimal remaining = order.getAmountRemaining() == null
                ? order.getAmount().subtract(order.getFilledAmount()) : order.getAmountRemaining();
        if (remaining.signum() <= 0) {
            return "";
        }
        return "Filled " + amount8(number(order.getFilledAmount())) + " of "
                + amount8(number(order.getAmount())) + " " + base(market) + " · "
                + amount8(number(remaining)) + " remaining";
    }

    private List<RawRowView> rawRows(List<Transaction> entries, int page, int pageSize) {
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, entries.size());
        List<Transaction> sorted = entries.stream()
                .sorted(Comparator.comparing(Transaction::getExecutedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        List<RawRowView> rows = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            Transaction entry = sorted.get(i);
            String type = entry.getType() == null ? DASH : entry.getType();
            rows.add(new RawRowView(i + 1,
                    entry.getExecutedAt() == null ? DASH : display.minute(entry.getExecutedAt()),
                    type, typeClass(type),
                    amountWithCurrency(entry.getSentAmount(), entry.getSentCurrency()),
                    amountWithCurrency(entry.getReceivedAmount(), entry.getReceivedCurrency()),
                    amountWithCurrency(entry.getFeesAmount(), entry.getFeesCurrency())));
        }
        return rows;
    }

    private FiltersView filters(LedgerRequest request, String market, boolean raw) {
        return new FiltersView(request.getSide(), market, request.getType(), request.getStatus(),
                raw ? RAW_VIEW : DEFAULT_VIEW,
                HistoryPeriods.hasCustomRange(request.getFromDate(), request.getToDate()) ? ""
                        : HistoryPeriods.preset(request.getPeriod()),
                request.getFromDate(), request.getFromTime(), request.getToDate(), request.getToTime(),
                marketOptions(market), HistoryPeriods.OPTIONS);
    }

    private TotalsView totals(List<OrderResponse> orders, Basis basis, double currentBid,
                              double balance, String market, HistoryPeriods.Range range) {
        double fees = orders.stream().mapToDouble(order -> orderFee(order, market)).sum();
        double realised = 0;
        double unrealised = 0;
        for (OrderResponse order : orders) {
            double result = result(order, basis, currentBid);
            if (Double.isNaN(result)) {
                continue;
            }
            if (isBuy(order)) {
                unrealised += result;
            } else {
                realised += result;
            }
        }
        return new TotalsView(range.label(), orders.size(), euro(fees),
                signed(realised), signClass(realised), signed(unrealised), signClass(unrealised),
                plain(balance) + " " + base(market));
    }

    private PagerView pager(LedgerRequest request, int total, int page, int pages) {
        int size = pageSize(request.getSize());
        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(fromIndex + size, total);
        String prev = page > 1 ? query(request, page - 1, size) : null;
        String next = page < pages ? query(request, page + 1, size) : null;
        List<SizeOption> sizes = PAGE_SIZES.stream()
                .map(candidate -> new SizeOption(String.valueOf(candidate),
                        query(request, 1, candidate), candidate == size))
                .toList();
        return new PagerView(total == 0 ? 0 : fromIndex + 1, toIndex, total, page, pages, size,
                prev, next, sizes);
    }

    // ---- the results ------------------------------------------------------------------------

    /**
     * Walks the market's fills oldest first, keeping an average cost: buys add their amount and
     * what they cost, sells take the average cost of what they sell. Inventory that was not bought
     * through the order history — a deposit — has no cost, so a sell of it counts at its own price.
     */
    private record Basis(NavigableMap<Long, Double> costBeforeByFill) {

        static Basis walk(List<Transaction> fills, String quote, String base) {
            NavigableMap<Long, Double> costBefore = new TreeMap<>();
            double quantity = 0;
            double cost = 0;
            for (Transaction fill : fills) {
                double amount = baseAmount(fill, base);
                double price = fill.getPriceAmount() == null ? 0 : fill.getPriceAmount().doubleValue();
                double fee = feeValue(fill, quote, base);
                costBefore.put(time(fill), quantity > 0 ? cost / quantity : price);
                if (base.equals(fill.getReceivedCurrency())) {
                    quantity += amount;
                    cost += amount * price + fee;
                } else {
                    double average = quantity > 0 ? cost / quantity : price;
                    quantity = Math.max(quantity - amount, 0);
                    cost = Math.max(cost - amount * average, 0);
                }
            }
            return new Basis(costBefore);
        }

        /**
         * @param when The fill's moment
         * @return What the inventory cost per unit just before that fill, or {@code NaN} when the
         *         fill is not in the walk
         */
        double averageBefore(Instant when) {
            Map.Entry<Long, Double> entry = costBeforeByFill.floorEntry(when.toEpochMilli());
            return entry == null ? Double.NaN : entry.getValue();
        }
    }

    /**
     * The balance trajectory: what the account's base-currency balance was after each fill.
     */
    private record Balance(double current, NavigableMap<Long, Double> afterByFill) {

        /**
         * @param order  The order
         * @param market The market
         * @return The balance after the order's fills, or after the fills that preceded it
         */
        String after(OrderResponse order) {
            Map.Entry<Long, Double> entry = order.getFills() == null || order.getFills().isEmpty()
                    ? afterByFill.floorEntry(created(order))
                    : lastFillAfter(order);
            return entry == null ? plain(current) : plain(entry.getValue());
        }

        private Map.Entry<Long, Double> lastFillAfter(OrderResponse order) {
            Map.Entry<Long, Double> latest = null;
            for (Fills fill : order.getFills()) {
                if (fill.getTimestamp() == null) {
                    continue;
                }
                Map.Entry<Long, Double> entry = afterByFill.floorEntry(fill.getTimestamp().toEpochMilli());
                if (entry != null && (latest == null || entry.getKey() > latest.getKey())) {
                    latest = entry;
                }
            }
            return latest;
        }
    }

    private double result(OrderResponse order, Basis basis, double currentBid) {
        if (order.getFills() == null || order.getFills().isEmpty()) {
            return Double.NaN;
        }
        boolean buy = isBuy(order);
        if (buy && !Double.isFinite(currentBid)) {
            return Double.NaN;
        }
        String market = order.getMarket() == null ? DEFAULT_MARKET : order.getMarket();
        double result = 0;
        for (Fills fill : order.getFills()) {
            double amount = number(fill.getAmount());
            double price = number(fill.getPrice());
            double fee = quoteFee(fill, market);
            if (buy) {
                result += currentBid * amount - (price * amount + fee);
            } else {
                double cost = fill.getTimestamp() == null ? Double.NaN
                        : basis.averageBefore(fill.getTimestamp());
                result += (price - (Double.isNaN(cost) ? price : cost)) * amount - fee;
            }
        }
        return result;
    }

    // ---- small helpers ----------------------------------------------------------------------

    private static boolean matchesSide(OrderResponse order, String side) {
        return !"buy".equalsIgnoreCase(side) && !"sell".equalsIgnoreCase(side)
                || side.equalsIgnoreCase(order.getSide());
    }

    private static boolean matchesType(OrderResponse order, String type) {
        if (type == null || type.isBlank() || !ORDER_TYPES.contains(type)) {
            return true;
        }
        return type.equalsIgnoreCase(order.getOrderType());
    }

    private static boolean matchesStatus(OrderResponse order, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        // The API calls an open order "new"; the filter spells it out.
        String wanted = "open".equalsIgnoreCase(status) ? "new" : status;
        return wanted.equalsIgnoreCase(order.getStatus());
    }

    private static boolean isBuy(OrderResponse order) {
        return "buy".equalsIgnoreCase(order.getSide());
    }

    private static long created(OrderResponse order) {
        return order.getCreated() == null ? 0 : order.getCreated();
    }

    private static long time(Transaction fill) {
        return fill.getExecutedAt() == null ? 0 : fill.getExecutedAt().toEpochMilli();
    }

    /**
     * @param fill The fill
     * @param base The market's base currency
     * @return How the fill moved the base currency: positive when it came in, negative when it went
     *         out — decided by which side of the trade the base currency is on, because a "buy" of
     *         another market can pay with it
     */
    private static double baseDelta(Transaction fill, String base) {
        return base.equals(fill.getReceivedCurrency()) ? number(fill.getReceivedAmount())
                : -number(fill.getSentAmount());
    }

    private static double baseAmount(Transaction fill, String base) {
        if (base.equals(fill.getReceivedCurrency())) {
            return number(fill.getReceivedAmount());
        }
        return number(fill.getSentAmount());
    }

    private static double feeValue(Transaction fill, String quote, String base) {
        if (fill.getFeesAmount() == null) {
            return 0;
        }
        if (quote.equals(fill.getFeesCurrency())) {
            return fill.getFeesAmount().doubleValue();
        }
        double price = fill.getPriceAmount() == null ? 0 : fill.getPriceAmount().doubleValue();
        if (base.equals(fill.getFeesCurrency())) {
            return fill.getFeesAmount().doubleValue() * price;
        }
        return 0;
    }

    /**
     * @param order  The order
     * @param market The market
     * @return The order's total fee in the quote currency. An order that filled sums its fills'
     *         converted fees — the fills know their own currency; an order that did not sums its
     *         fee directly, converting it through the average fill or limit price when it is paid
     *         in the base currency.
     */
    private double orderFee(OrderResponse order, String market) {
        if (order.getFills() != null && !order.getFills().isEmpty()) {
            double fee = 0;
            for (Fills fill : order.getFills()) {
                fee += quoteFee(fill, market);
            }
            return fee;
        }
        if (order.getFeePaid() == null) {
            return 0;
        }
        if (quote(market).equals(order.getFeeCurrency())) {
            return order.getFeePaid().doubleValue();
        }
        if (base(market).equals(order.getFeeCurrency())) {
            double amount = number(order.getFilledAmount());
            double price = amount > 0 ? number(order.getFilledAmountQuote()) / amount
                    : number(order.getPrice());
            return order.getFeePaid().doubleValue() * price;
        }
        return 0;
    }

    private double quoteFee(Fills fill, String market) {
        if (fill.getFee() == null) {
            return 0;
        }
        if (quote(market).equals(fill.getFeeCurrency())) {
            return fill.getFee().doubleValue();
        }
        double price = fill.getPrice() == null ? 0 : fill.getPrice().doubleValue();
        if (base(market).equals(fill.getFeeCurrency())) {
            return fill.getFee().doubleValue() * price;
        }
        return 0;
    }

    private static String statusLabel(String status) {
        if (status == null || status.isBlank()) {
            return DASH;
        }
        return switch (status.toLowerCase(Locale.ENGLISH)) {
            case "new" -> "open";
            case "partiallyfilled" -> "partially filled";
            default -> status.toLowerCase(Locale.ENGLISH);
        };
    }

    private static String statusClass(String status) {
        if (status == null) {
            return "";
        }
        return switch (status.toLowerCase(Locale.ENGLISH)) {
            case "filled" -> "ok";
            case "partiallyfilled" -> "warn";
            case "canceled", "cancelled", "expired" -> "bad";
            default -> "";
        };
    }

    private static String typeClass(String type) {
        if ("buy".equalsIgnoreCase(type)) {
            return "side-buy";
        }
        return "sell".equalsIgnoreCase(type) ? "side-sell" : "";
    }

    private static String signClass(double value) {
        return value >= 0 ? "pos" : "neg";
    }

    private static int pageSize(Integer size) {
        return size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE;
    }

    private static int clamp(Integer page) {
        return page == null ? 1 : Math.max(page, 1);
    }

    private static int pages(int total, int size) {
        return Math.max((total + size - 1) / size, 1);
    }

    private static double number(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }

    private static String amount8(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).toPlainString();
    }

    private static String amountText(BigDecimal amountQuote, String market) {
        return amountQuote == null ? DASH
                : euro(amountQuote.doubleValue()) + " " + quote(market);
    }

    private static String plain(double value) {
        BigDecimal decimal = BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return decimal.signum() == 0 ? "0" : decimal.toPlainString();
    }

    private static String euro(double value) {
        return ViewText.euro(value);
    }

    private static String euro4(double value) {
        return "€" + String.format(Locale.US, "%,.4f", Math.abs(value));
    }

    private static String signed(double value) {
        return ViewText.signed(value);
    }

    private static String amountWithCurrency(BigDecimal amount, String currency) {
        if (amount == null || currency == null || amount.signum() == 0) {
            return DASH;
        }
        return EUR.equals(currency) ? euro(amount.doubleValue()) + " EUR"
                : plain(amount.doubleValue()) + " " + currency;
    }

    private static String base(String market) {
        return market == null ? "" : market.split("-")[0];
    }

    private static String quote(String market) {
        String[] parts = market == null ? new String[0] : market.split("-");
        return parts.length > 1 ? parts[1] : EUR;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String query(LedgerRequest request, int page, int size) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tab", "ledger");
        add(params, "side", request.getSide());
        add(params, "market", request.getMarket());
        add(params, "type", request.getType());
        add(params, "status", request.getStatus());
        add(params, "view", request.getView());
        if (!HistoryPeriods.hasCustomRange(request.getFromDate(), request.getToDate())) {
            add(params, "period", request.getPeriod());
        }
        add(params, "fromDate", request.getFromDate());
        add(params, "fromTime", request.getFromTime());
        add(params, "toDate", request.getToDate());
        add(params, "toTime", request.getToTime());
        params.put("size", String.valueOf(size));
        if (page > 1) {
            params.put("page", String.valueOf(page));
        }
        return "?" + params.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + UriUtils.encodeQueryParam(entry.getValue(),
                        StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static void add(Map<String, String> params, String key, String value) {
        if (notBlank(value)) {
            params.put(key, value);
        }
    }
}
