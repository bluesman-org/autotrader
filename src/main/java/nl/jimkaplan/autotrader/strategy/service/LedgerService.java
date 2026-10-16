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
import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * Builds the Bitvavo-ledger tab: the account the engine trades on, read through the system-wide
 * Bitvavo API key — its orders for the selected market and period with their fills, the raw
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
    private static final String RANGE_HINT =
            "Unrealised needs today as the end date — end the range with today to see it.";
    private static final String DEFAULT_MARKET = "BTC-EUR";
    private static final String EUR = "EUR";
    private static final String DEFAULT_VIEW = "orders";
    private static final String RAW_VIEW = "raw";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final List<Integer> PAGE_SIZES = List.of(5, 10, 25, 50);
    private static final List<String> ORDER_TYPES = List.of("market", "limit", "stopLoss",
            "stopLossLimit", "takeProfit", "takeProfitLimit");
    private static final List<Option> STATUS_OPTIONS = List.of(
            new Option("open", "Open"),
            new Option("awaitingTrigger", "Awaiting trigger"),
            new Option("partiallyFilled", "Partially filled"),
            new Option("filled", "Filled"),
            new Option("canceled", "Canceled"),
            new Option("expired", "Expired"));
    private static final List<String> STATUS_VALUES = STATUS_OPTIONS.stream().map(Option::value).toList();
    private static final int ORDER_LIMIT = 1000;
    /** The API's maximum for one account-history page (a larger value is rejected with code 417). */
    private static final int HISTORY_PAGE_SIZE = 100;
    private static final int MAX_HISTORY_PAGES = 25;

    /**
     * The cost walk starts here: every buy and sell of the account, per market, is needed to know
     * what inventory cost, and the account did not exist before Bitvavo did.
     */
    private static final long GENESIS_MILLIS = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();

    private final BitvavoApiClient bitvavoApiClient;
    private final BitvavoCredentialsService credentialsService;
    private final LiveStrategyService liveStrategyService;
    private final DisplayZone display;
    private final HistoryPeriods periods;
    private final Clock clock;

    @Autowired
    public LedgerService(BitvavoApiClient bitvavoApiClient,
                         BitvavoCredentialsService credentialsService,
                         LiveStrategyService liveStrategyService,
                         @Value("${autotrader.ui.time-zone:}") String timeZone) {
        this(bitvavoApiClient, credentialsService, liveStrategyService, new DisplayZone(timeZone),
                Clock.systemUTC());
    }

    LedgerService(BitvavoApiClient bitvavoApiClient, BitvavoCredentialsService credentialsService,
                  LiveStrategyService liveStrategyService, DisplayZone display, Clock clock) {
        this.bitvavoApiClient = bitvavoApiClient;
        this.credentialsService = credentialsService;
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
        Optional<BitvavoCredentials> account = credentialsService.get();
        if (account.isEmpty()) {
            return new LedgerView(
                    "No Bitvavo API key is configured, so the account cannot be read. Store the "
                            + "account's key on the Settings page (/autotrader/ui/settings) — the "
                            + "ledger uses the same key the engine trades with.",
                    price, filters, null, List.of(), pager(request, 0, 1, 1), raw, List.of(),
                    pager(request, 0, 1, 1));
        }
        BitvavoCredentials credentials = account.get();
        try {
            return ledger(request, range, market, raw, book, price, filters, credentials);
        } catch (RestClientException e) {
            log.warn("Could not read the Bitvavo ledger for {}: {}", market, e.getMessage());
            return new LedgerView("Could not read the Bitvavo account: " + e.getMessage(), price,
                    filters, null, List.of(), pager(request, 0, 1, 1), raw, List.of(),
                    pager(request, 0, 1, 1));
        }
    }

    private LedgerView ledger(LedgerRequest request, HistoryPeriods.Range range, String market,
                              boolean raw, GetBookResponse book, PriceView price, FiltersView filters,
                              BitvavoCredentials credentials) {
        List<OrderResponse> periodOrders = orders(credentials, market, range);
        List<Transaction> marketFills = marketFills(credentials, market, clock.instant().toEpochMilli());
        Balance balance = balance(credentials, market, marketFills);
        double currentBid = book == null || book.getBid() == null ? Double.NaN : book.getBid().doubleValue();
        Basis basis = Basis.walk(marketFills, quote(market), base(market));

        List<OrderResponse> filtered = periodOrders.stream()
                .filter(order -> matchesSide(order, request.getSide()))
                .filter(order -> matchesType(order, filters.type()))
                .filter(order -> matchesStatus(order, filters.status()))
                .sorted(Comparator.comparingLong(LedgerService::created).reversed())
                .toList();

        int pageSize = pageSize(request.getSize());
        int pages = pages(filtered.size(), pageSize);
        int page = Math.min(clamp(request.getPage()), pages);
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, filtered.size());
        List<OrderRowView> rows = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            rows.add(row(filtered.get(i), i + 1, basis, balance, market));
        }

        List<Transaction> rawEntries = raw ? history(credentials, null, range.fromMillis(), range.toMillis())
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

    /**
     * @param values The submitted values, possibly null
     * @param known  The values the filter understands
     * @return The submitted values that are known, in the catalogue's order, so the select shows
     *         exactly what the request means
     */
    private static List<String> selected(List<String> values, List<String> known) {
        return values == null ? List.of() : known.stream().filter(values::contains).toList();
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

    private List<OrderResponse> orders(BitvavoCredentials credentials, String market,
                                       HistoryPeriods.Range range) {
        String endpoint = UriComponentsBuilder.fromPath("/orders")
                .queryParam("market", market)
                .queryParam("start", range.fromMillis())
                .queryParam("end", range.toMillis())
                .queryParam("limit", ORDER_LIMIT)
                .build().encode().toUriString();
        OrderResponse[] response = bitvavoApiClient.get(endpoint, OrderResponse[].class,
                credentials.getApiKey(), credentials.getApiSecret());
        return response == null ? List.of() : List.of(response);
    }

    /**
     * Every buy and sell of the market, oldest first. The cost walk must see what happened before
     * the period — a sell's inventory was bought earlier — so this reads from the account's start,
     * not from the period's.
     */
    private List<Transaction> marketFills(BitvavoCredentials credentials, String market, long toMillis) {
        String base = base(market);
        List<Transaction> fills = new ArrayList<>();
        for (String type : List.of("buy", "sell")) {
            for (Transaction entry : history(credentials, type, GENESIS_MILLIS, toMillis)) {
                if (base.equals(entry.getReceivedCurrency()) || base.equals(entry.getSentCurrency())) {
                    fills.add(entry);
                }
            }
        }
        fills.sort(Comparator.comparing(Transaction::getExecutedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return fills;
    }

    private List<Transaction> history(BitvavoCredentials credentials, String type, long fromMillis,
                                      long toMillis) {
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
                    GetAccountHistoryResponse.class, credentials.getApiKey(), credentials.getApiSecret());
            if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                break;
            }
            entries.addAll(response.getItems());
            if (response.getTotalPages() == null || page >= response.getTotalPages()) {
                break;
            }
            if (page == MAX_HISTORY_PAGES) {
                // The walk would be silently incomplete past this page count (roughly 25 × 100
                // entries per read), so the truncation is at least visible. PLAN item 23 covers
                // bounding or caching the read itself.
                log.warn("Account history read stopped at {} entries (type {}); the average-cost walk "
                        + "may be incomplete", entries.size(), type == null ? "all" : type);
            }
        }
        return entries;
    }

    /**
     * The account's current balance of the market's base currency, plus a way to ask what it was
     * after a given order: the walk counts fills backwards from today's balance, so "Balance after"
     * counts only filled orders.
     */
    private Balance balance(BitvavoCredentials credentials, String market,
                            List<Transaction> marketFills) {
        String endpoint = UriComponentsBuilder.fromPath("/balance")
                .queryParam("symbol", base(market))
                .build().encode().toUriString();
        GetAccountBalanceResponse[] balances = bitvavoApiClient.get(endpoint,
                GetAccountBalanceResponse[].class, credentials.getApiKey(), credentials.getApiSecret());
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

    private OrderRowView row(OrderResponse order, int number, Basis basis, Balance balance,
                             String market) {
        boolean buy = isBuy(order);
        double amount = number(order.getFilledAmount());
        double quoteAmount = number(order.getFilledAmountQuote());
        double cashFee = cashFee(order, market);
        double result = result(order, basis);
        // The average of the fills, so it is a dash until something filled; what a waiting order
        // carries is named under its status instead.
        String avgPrice = amount > 0 ? euro(quoteAmount / amount) : DASH;
        double netCash = buy ? -(quoteAmount + cashFee) : quoteAmount - cashFee;
        return new OrderRowView(number,
                order.getCreated() == null ? DASH : display.minute(Instant.ofEpochMilli(order.getCreated())),
                buy ? "BUY" : "SELL", buy ? "side-buy" : "side-sell",
                order.getOrderType() == null ? DASH : order.getOrderType(),
                order.getMarket() == null ? market : order.getMarket(),
                order.getAmount() == null ? amountText(order.getAmountQuote(), market) : amount8(number(order.getAmount())),
                avgPrice, statusLabel(order.getStatus()), statusClass(order.getStatus()),
                triggerText(order), balance.after(order), fee(order, market),
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
        List<FillFee> fees = fillFees(order, market);
        List<FillRowView> rows = new ArrayList<>();
        for (int i = 0; i < order.getFills().size(); i++) {
            Fills fill = order.getFills().get(i);
            FillFee fee = fees.get(i);
            double amount = number(fill.getAmount());
            double price = number(fill.getPrice());
            // Only a fee charged in the quote currency moves the cash; a coin fee leaves the euro
            // flow alone and shows in the fee column instead. The fee is the same one the row shows,
            // so an order's cash equals the sum of its fills'.
            double cashFee = quote(market).equals(fee.currency()) ? fee.amount() : 0;
            double netCash = isBuy(order) ? -(amount * price + cashFee) : amount * price - cashFee;
            rows.add(new FillRowView(
                    fill.getTimestamp() == null ? DASH : display.clockSecond(fill.getTimestamp()),
                    amount8(amount), euro(price), feeText(fee),
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
        boolean custom = HistoryPeriods.usesCustomRange(request.getPeriod(), request.getFromDate(),
                request.getToDate());
        return new FiltersView(request.getSide(), market, selected(request.getType(), ORDER_TYPES),
                selected(request.getStatus(), STATUS_VALUES),
                raw ? RAW_VIEW : DEFAULT_VIEW,
                custom ? "" : HistoryPeriods.preset(request.getPeriod()),
                custom ? request.getFromDate() : null,
                HistoryPeriods.shownFromTime(custom ? request.getFromTime() : null),
                custom ? request.getToDate() : null,
                HistoryPeriods.shownToTime(custom ? request.getToTime() : null),
                HistoryPeriods.DEFAULT_PRESET, marketOptions(market),
                ORDER_TYPES.stream().map(type -> new Option(type, type)).toList(), STATUS_OPTIONS,
                HistoryPeriods.OPTIONS);
    }

    private TotalsView totals(List<OrderResponse> orders, Basis basis, double currentBid,
                              double balance, String market, HistoryPeriods.Range range) {
        String feesText = feesText(orders, market);
        double realised = orders.stream().mapToDouble(order -> result(order, basis)).filter(Double::isFinite)
                .sum();
        double unrealised = unrealised(basis, currentBid, range);
        return new TotalsView(range.label(), orders.size(), feesText,
                signed(realised), signClass(realised),
                Double.isNaN(unrealised) ? DASH : signed(unrealised),
                Double.isNaN(unrealised) ? "" : signClass(unrealised),
                plain(balance) + " " + base(market), unrealisedHint(basis, range, currentBid));
    }

    /**
     * @return The open position's result — what the held coins are worth at the current bid against
     *         what they cost — or {@code NaN} when there is nothing to show: a range that ends before
     *         today cannot value a past holding at today's price, and an empty holding has no result
     */
    private double unrealised(Basis basis, double currentBid, HistoryPeriods.Range range) {
        if (basis.quantity() <= 0 || !Double.isFinite(currentBid) || !endsToday(range)) {
            return Double.NaN;
        }
        return basis.quantity() * (currentBid - basis.averageCost());
    }

    private boolean endsToday(HistoryPeriods.Range range) {
        // The service's clock, so the rule is the same in a test as on the page.
        LocalDate end = Instant.ofEpochMilli(range.toMillis()).atZone(display.zone()).toLocalDate();
        return end.equals(LocalDate.now(clock));
    }

    private String unrealisedHint(Basis basis, HistoryPeriods.Range range, double currentBid) {
        if (!endsToday(range)) {
            return RANGE_HINT;
        }
        if (basis.quantity() <= 0) {
            return "Nothing is held, so there is no unrealised result.";
        }
        return Double.isFinite(currentBid) ? ""
                : "The current price could not be read, so there is no unrealised figure.";
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
    private record Basis(NavigableMap<Long, Double> costBeforeByFill, double quantity, double cost) {

        /**
         * @return What one held unit cost on average, or {@code NaN} when nothing is held
         */
        double averageCost() {
            return quantity > 0 ? cost / quantity : Double.NaN;
        }

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
            return new Basis(costBefore, quantity, cost);
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

    /**
     * @param order The order
     * @param basis The market's cost walk
     * @return What a sell realised, and {@code NaN} for anything else: a buy shows no result of its
     *         own — the sell that closes it carries it, at the average cost of the coins it sold
     */
    private double result(OrderResponse order, Basis basis) {
        if (isBuy(order) || order.getFills() == null || order.getFills().isEmpty()) {
            return Double.NaN;
        }
        String market = order.getMarket() == null ? DEFAULT_MARKET : order.getMarket();
        List<FillFee> fees = fillFees(order, market);
        double result = 0;
        for (int i = 0; i < order.getFills().size(); i++) {
            Fills fill = order.getFills().get(i);
            double amount = number(fill.getAmount());
            double price = number(fill.getPrice());
            double fee = quoteValue(fees.get(i), market, price);
            double cost = fill.getTimestamp() == null ? Double.NaN
                    : basis.averageBefore(fill.getTimestamp());
            result += (price - (Double.isNaN(cost) ? price : cost)) * amount - fee;
        }
        return result;
    }

    // ---- small helpers ----------------------------------------------------------------------

    private static boolean matchesSide(OrderResponse order, String side) {
        return !"buy".equalsIgnoreCase(side) && !"sell".equalsIgnoreCase(side)
                || side.equalsIgnoreCase(order.getSide());
    }

    private static boolean matchesType(OrderResponse order, List<String> types) {
        // The filter bar hands over the normalized lists, so a value the filter cannot use is
        // already gone: an empty list means every type.
        return types.isEmpty()
                || types.stream().anyMatch(type -> type.equalsIgnoreCase(order.getOrderType()));
    }

    private static boolean matchesStatus(OrderResponse order, List<String> statuses) {
        return statuses.isEmpty() || statuses.stream()
                .anyMatch(status -> matchesOneStatus(order.getStatus(), status));
    }

    private static boolean matchesOneStatus(String actual, String wanted) {
        // The API calls a resting order "new" and a stop or take-profit waiting for its trigger
        // "awaitingTrigger"; "Open" covers both, and the other choices name one status each.
        if ("open".equalsIgnoreCase(wanted)) {
            return "new".equalsIgnoreCase(actual) || "awaitingtrigger".equalsIgnoreCase(actual);
        }
        return wanted.equalsIgnoreCase(actual);
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
        // A missing currency follows the same rule as the orders side: it counts as the quote.
        String currency = fill.getFeesCurrency() == null || fill.getFeesCurrency().isBlank()
                ? quote : fill.getFeesCurrency();
        if (quote.equals(currency)) {
            return fill.getFeesAmount().doubleValue();
        }
        double price = fill.getPriceAmount() == null ? 0 : fill.getPriceAmount().doubleValue();
        return base.equals(currency) ? fill.getFeesAmount().doubleValue() * price : 0;
    }

    /**
     * @param order  The order
     * @param market The market
     * @return What the exchange charged for the order, in the currencies it charged it in — one
     *         part per currency, so a bitcoin fee reads as bitcoin and never as euro — or a dash
     *         when nothing was charged
     */
    private String fee(OrderResponse order, String market) {
        return partsText(feeParts(order, market));
    }

    /**
     * A fill's fee with the currency it was charged in.
     */
    private record FillFee(double amount, String currency) {
    }

    /**
     * The fees of an order's fills, in fill order. The exchange reports every fill's own fee and
     * currency; an order whose fills report no fee at all splits its order-level {@code feePaid}
     * over them by amount, so the row, its fill lines, the cash flow, the P&amp;L and the period
     * total always tell one story.
     */
    private static List<FillFee> fillFees(OrderResponse order, String market) {
        if (order.getFills() == null || order.getFills().isEmpty()) {
            return List.of();
        }
        boolean reported = order.getFills().stream()
                .anyMatch(fill -> fill.getFee() != null && fill.getFee().signum() != 0);
        if (reported) {
            return order.getFills().stream()
                    .map(fill -> new FillFee(number(fill.getFee()), currency(fill.getFeeCurrency(), market)))
                    .toList();
        }
        double orderFee = number(order.getFeePaid());
        double total = order.getFills().stream().mapToDouble(fill -> number(fill.getAmount())).sum();
        String currency = currency(order.getFeeCurrency(), market);
        if (orderFee == 0 || total <= 0) {
            return order.getFills().stream().map(fill -> new FillFee(0, currency)).toList();
        }
        List<FillFee> fees = new ArrayList<>();
        double allocated = 0;
        for (int i = 0; i < order.getFills().size(); i++) {
            // The last fill takes the remainder, so the shares add up to the order's fee exactly.
            double share = i == order.getFills().size() - 1 ? orderFee - allocated
                    : orderFee * number(order.getFills().get(i).getAmount()) / total;
            allocated += share;
            fees.add(new FillFee(share, currency));
        }
        return fees;
    }

    /**
     * @param fee    The fill's fee
     * @param market The market
     * @param price  The fill's price
     * @return What the fee costs in the market's quote currency — a coin fee is worth its own price
     *         — which is what the P&amp;L, expressed in euro, needs
     */
    private static double quoteValue(FillFee fee, String market, double price) {
        if (quote(market).equals(fee.currency())) {
            return fee.amount();
        }
        return base(market).equals(fee.currency()) ? fee.amount() * price : 0;
    }

    /**
     * @param order The order
     * @return For a resting or trigger-waiting order: its trigger price and, when the order type
     *         carries one, its limit price; empty once it traded or when it carries neither
     */
    private static String triggerText(OrderResponse order) {
        boolean waiting = "awaitingtrigger".equalsIgnoreCase(order.getStatus())
                || "new".equalsIgnoreCase(order.getStatus());
        if (!waiting) {
            return "";
        }
        boolean hasTrigger = order.getTriggerPrice() != null && order.getTriggerPrice().signum() != 0;
        boolean hasPrice = order.getPrice() != null && order.getPrice().signum() != 0;
        if (!hasTrigger && !hasPrice) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (hasTrigger) {
            parts.add("trigger " + euro(order.getTriggerPrice().doubleValue()));
        }
        if (hasPrice) {
            boolean limit = order.getOrderType() != null
                    && order.getOrderType().toLowerCase(Locale.ENGLISH).endsWith("limit");
            parts.add((limit ? "limit " : "price ") + euro(order.getPrice().doubleValue()));
        }
        return String.join(" · ", parts);
    }

    /**
     * @param fee The fill's fee
     * @return The fee in its own currency — euro amounts at their own precision — or a dash when
     *         the fill paid nothing
     */
    private static String feeText(FillFee fee) {
        return fee.amount() == 0 ? DASH
                : EUR.equals(fee.currency()) ? euro4(fee.amount()) : money(fee.amount(), fee.currency());
    }

    /**
     * @return The amount written the way its own currency reads: euro amounts keep the euro sign,
     *         everything else shows its ticker
     */
    private static String money(double amount, String currency) {
        return EUR.equals(currency) ? euro(amount) : plain(amount) + " " + currency;
    }

    /**
     * @param currency The currency a fee reports, possibly missing
     * @param market   The market
     * @return The currency, or the market's quote currency when it is missing — one rule for a
     *         missing currency keeps the row, the cash flow and the period total in agreement
     */
    private static String currency(String currency, String market) {
        return currency == null || currency.isBlank() ? quote(market) : currency;
    }

    private static String partsText(List<Fee> parts) {
        if (parts.isEmpty()) {
            return DASH;
        }
        return parts.stream()
                .map(part -> EUR.equals(part.currency()) ? euro(part.amount())
                        : money(part.amount(), part.currency()))
                .collect(Collectors.joining(" + "));
    }

    /**
     * @return The fee the order paid in the market's quote currency. A fee taken in the coin does
     *         not move the cash, so it is not counted into the cash flow; its euro value still
     *         enters the P&amp;L, which is in euro.
     */
    private double cashFee(OrderResponse order, String market) {
        double fee = 0;
        for (Fee part : feeParts(order, market)) {
            if (quote(market).equals(part.currency())) {
                fee += part.amount();
            }
        }
        return fee;
    }

    /**
     * A fee with the currency it was charged in.
     */
    private record Fee(String currency, double amount) {
    }

    /**
     * The fills' fees are the finer truth — the exchange reports each fill's own currency — so they
     * are the source when they carry one; an order whose fills report no fee (an unsettled fill, a
     * fee charged at order level) falls back to {@code feePaid} so the row, the fills and the
     * period total always tell one story.
     *
     * @param order  The order
     * @param market The market
     * @return One part per currency the order's fee was charged in
     */
    private static List<Fee> feeParts(OrderResponse order, String market) {
        Map<String, Double> byCurrency = new LinkedHashMap<>();
        for (FillFee fee : fillFees(order, market)) {
            if (fee.amount() != 0) {
                byCurrency.merge(fee.currency(), fee.amount(), Double::sum);
            }
        }
        if (!byCurrency.isEmpty()) {
            return byCurrency.entrySet().stream()
                    .map(part -> new Fee(part.getKey(), part.getValue())).toList();
        }
        double feePaid = number(order.getFeePaid());
        if (feePaid == 0) {
            return List.of();
        }
        return List.of(new Fee(currency(order.getFeeCurrency(), market), feePaid));
    }

    /**
     * @param orders The period's orders
     * @param market The market
     * @return The period's fees as charged: the euro part, plus one part per other currency — the
     *         fees are shown in the currency they were taken in, never converted for the display
     */
    private String feesText(List<OrderResponse> orders, String market) {
        double euro = 0;
        Map<String, Double> others = new LinkedHashMap<>();
        for (OrderResponse order : orders) {
            for (Fee fee : feeParts(order, market)) {
                if (EUR.equals(fee.currency())) {
                    euro += fee.amount();
                } else {
                    others.merge(fee.currency(), fee.amount(), Double::sum);
                }
            }
        }
        List<String> parts = new ArrayList<>();
        if (euro != 0 || others.isEmpty()) {
            parts.add(euro(euro));
        }
        others.forEach((currency, amount) -> parts.add(plain(amount) + " " + currency));
        return String.join(" + ", parts);
    }

    private static String statusLabel(String status) {
        if (status == null || status.isBlank()) {
            return DASH;
        }
        return switch (status.toLowerCase(Locale.ENGLISH)) {
            case "new" -> "open";
            case "partiallyfilled" -> "partially filled";
            case "awaitingtrigger" -> "awaiting trigger";
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
        return ViewText.decimal(value, 8);
    }

    private static String amountText(BigDecimal amountQuote, String market) {
        return amountQuote == null ? DASH
                : euro(amountQuote.doubleValue()) + " " + quote(market);
    }

    private static String plain(double value) {
        return ViewText.trimmedDecimal(value, 8);
    }

    private static String euro(double value) {
        return ViewText.euro(value);
    }

    private static String euro4(double value) {
        return ViewText.euro(value, 4);
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
        List<String> pairs = new ArrayList<>();
        pairs.add("tab=ledger");
        add(pairs, "side", request.getSide());
        add(pairs, "market", request.getMarket());
        addAll(pairs, "type", request.getType());
        addAll(pairs, "status", request.getStatus());
        add(pairs, "view", request.getView());
        if (HistoryPeriods.usesCustomRange(request.getPeriod(), request.getFromDate(),
                request.getToDate())) {
            add(pairs, "fromDate", request.getFromDate());
            add(pairs, "fromTime", request.getFromTime());
            add(pairs, "toDate", request.getToDate());
            add(pairs, "toTime", request.getToTime());
        } else {
            add(pairs, "period", request.getPeriod());
        }
        pairs.add("size=" + size);
        if (page > 1) {
            pairs.add("page=" + page);
        }
        return "?" + pairs.stream().map(LedgerService::encodedPair)
                .collect(Collectors.joining("&"));
    }

    private static String encodedPair(String pair) {
        int at = pair.indexOf('=');
        return pair.substring(0, at) + "="
                + UriUtils.encodeQueryParam(pair.substring(at + 1), StandardCharsets.UTF_8);
    }

    private static void add(List<String> pairs, String key, String value) {
        if (notBlank(value)) {
            pairs.add(key + "=" + value);
        }
    }

    private static void addAll(List<String> pairs, String key, List<String> values) {
        if (values != null) {
            values.stream().filter(LedgerService::notBlank)
                    .forEach(value -> pairs.add(key + "=" + value));
        }
    }
}
