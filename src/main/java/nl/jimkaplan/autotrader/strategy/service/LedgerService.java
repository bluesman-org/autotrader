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
import nl.jimkaplan.autotrader.service.MarketService;
import nl.jimkaplan.autotrader.strategy.controller.LedgerRequest;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.FillRowView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.FiltersView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.HeaderView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.OrderRowView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.PairTotalView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.PriceView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.RawRowView;
import nl.jimkaplan.autotrader.strategy.controller.LedgerView.RawTotalView;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 *
 * <p>The orders' columns sort on their underlying values, not their displayed text, and the
 * all-pairs view sums the filtered orders per market. The raw view filters on the exchange's
 * transaction types and sums them per type and currency, so staking income and external transfers
 * read as totals. The orders are the spot account's — the Exchange API exposes no margin orders or
 * margin trades — and the raw history can return types its published list does not name, which the
 * filter picks up.</p>
 */
@Slf4j
@Service
public class LedgerService {

    private static final String DASH = "—";
    private static final String RANGE_HINT =
            "Unrealised needs today as the end date — end the range with today to see it.";
    private static final String DEFAULT_MARKET = "BTC-EUR";
    private static final String ALL = "all";
    private static final String EUR = "EUR";
    private static final String DEFAULT_VIEW = "orders";
    private static final String RAW_VIEW = "raw";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final List<Integer> PAGE_SIZES = List.of(5, 10, 25, 50);
    private static final String DEFAULT_SORT = "date";
    private static final List<Option> ORDER_TYPE_OPTIONS = List.of(
            new Option("market", "market"),
            new Option("limit", "limit"),
            new Option("stopLoss", "stopLoss"),
            new Option("stopLossLimit", "stopLossLimit"),
            new Option("takeProfit", "takeProfit"),
            new Option("takeProfitLimit", "takeProfitLimit"));
    // The exchange's transaction types (GET /account/history): orders, transfers, income and the
    // administrative ones. The raw view filters on these instead of the order types.
    private static final List<Option> RAW_TYPE_OPTIONS = List.of(
            new Option("buy", "Buy"),
            new Option("sell", "Sell"),
            new Option("deposit", "Deposit"),
            new Option("withdrawal", "Withdrawal"),
            new Option("withdrawal_cancelled", "Withdrawal cancelled"),
            new Option("staking", "Staking"),
            new Option("fixed_staking", "Fixed staking"),
            new Option("rebate", "Rebate"),
            new Option("affiliate", "Affiliate"),
            new Option("distribution", "Distribution"),
            new Option("internal_transfer", "Internal transfer"),
            new Option("loan", "Loan"),
            new Option("external_transferred_funds", "External transferred funds"),
            new Option("manually_assigned", "Manually assigned"),
            new Option("manually_assigned_bitvavo", "Manually assigned (Bitvavo)"));
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
    private final MarketService marketService;
    private final DisplayZone display;
    private final HistoryPeriods periods;
    private final Clock clock;

    @Autowired
    public LedgerService(BitvavoApiClient bitvavoApiClient,
                         BitvavoCredentialsService credentialsService,
                         LiveStrategyService liveStrategyService,
                         MarketService marketService,
                         @Value("${autotrader.ui.time-zone:}") String timeZone) {
        this(bitvavoApiClient, credentialsService, liveStrategyService, marketService,
                new DisplayZone(timeZone), Clock.systemUTC());
    }

    LedgerService(BitvavoApiClient bitvavoApiClient, BitvavoCredentialsService credentialsService,
                  LiveStrategyService liveStrategyService, MarketService marketService,
                  DisplayZone display, Clock clock) {
        this.bitvavoApiClient = bitvavoApiClient;
        this.credentialsService = credentialsService;
        this.liveStrategyService = liveStrategyService;
        this.marketService = marketService;
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
        boolean all = ALL.equals(market);
        boolean raw = RAW_VIEW.equalsIgnoreCase(request.getView());
        // "All pairs" has no single current price or book, so the price block is hidden and the
        // totals value every held pair from the exchange's whole book list instead.
        GetBookResponse book = all ? null : book(market);
        Map<String, GetBookResponse> books = all ? books()
                : book == null ? Map.of() : Map.of(market, book);
        PriceView price = all ? null : price(market, book);
        FiltersView filters = filters(request, market, raw);
        Optional<BitvavoCredentials> account = credentialsService.get();
        if (account.isEmpty()) {
            return new LedgerView(
                    "No Bitvavo API key is configured, so the account cannot be read. Store the "
                            + "account's key on the Settings page (/autotrader/ui/settings) — the "
                            + "ledger uses the same key the engine trades with.",
                    price, filters, List.of(), null, List.of(), pager(request, 0, 1, 1, null, null), raw,
                    List.of(), pager(request, 0, 1, 1, null, null), List.of(), List.of());
        }
        BitvavoCredentials credentials = account.get();
        try {
            return ledger(request, range, market, all, raw, books, price, filters, credentials);
        } catch (RestClientException e) {
            log.warn("Could not read the Bitvavo ledger for {}: {}", market, e.getMessage());
            return new LedgerView("Could not read the Bitvavo account: " + e.getMessage(), price,
                    filters, List.of(), null, List.of(), pager(request, 0, 1, 1, null, null), raw, List.of(),
                    pager(request, 0, 1, 1, null, null), List.of(), List.of());
        }
    }

    private LedgerView ledger(LedgerRequest request, HistoryPeriods.Range range, String market,
                              boolean all, boolean raw, Map<String, GetBookResponse> books,
                              PriceView price, FiltersView filters,
                              BitvavoCredentials credentials) {
        List<String> markets = all ? ledgerMarkets() : List.of(market);
        List<String> skipped = new ArrayList<>();
        List<OrderResponse> periodOrders = orders(credentials, markets, range, skipped);
        Map<String, List<Transaction>> fills = fillsByBase(credentials,
                clock.instant().toEpochMilli());
        Map<String, Balance> balances = balances(credentials, fills);
        Map<String, Basis> bases = bases(fills, markets);

        List<String> typeFilters = filters.type();
        List<String> statusFilters = filters.status();
        List<OrderResponse> filtered = periodOrders.stream()
                .filter(order -> matchesSide(order, request.getSide()))
                .filter(order -> matchesType(order, typeFilters))
                .filter(order -> matchesStatus(order, statusFilters))
                .toList();
        String sortKey = sortKey(request.getSort());
        boolean ascending = "asc".equalsIgnoreCase(request.getDir());
        Map<OrderResponse, Double> results = results(filtered, bases, market);
        Map<OrderResponse, Double> euroFees = euroFees(filtered, market);
        List<OrderResponse> sorted = sorted(filtered, request, balances, market, results, euroFees);

        int pageSize = pageSize(request.getSize());
        int pages = pages(sorted.size(), pageSize);
        int page = Math.min(clamp(request.getPage()), pages);
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, sorted.size());
        List<OrderRowView> rows = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            OrderResponse order = sorted.get(i);
            rows.add(row(order, i + 1, balances, market,
                    results.getOrDefault(order, Double.NaN)));
        }

        List<Transaction> rawEntries = raw ? history(credentials, null, range.fromMillis(), range.toMillis())
                : List.of();
        if (raw) {
            // Rebuild with the transaction types this period actually contains, so an
            // undocumented type (margin collateral, for example) is offered and can be selected.
            filters = filters(request, market, true, rawEntries);
        }
        List<Transaction> rawFiltered = raw ? filterRaw(rawEntries, filters.type()) : List.of();
        int rawPages = pages(rawFiltered.size(), pageSize);
        int rawPage = Math.min(clamp(request.getPage()), rawPages);
        List<RawRowView> rawRows = raw ? rawRows(rawFiltered, rawPage, pageSize) : List.of();
        List<PairTotalView> pairTotals = all && !raw
                ? pairTotals(filtered, results, euroFees, market) : List.of();
        List<RawTotalView> rawTotals = raw ? rawTotals(rawFiltered) : List.of();

        return new LedgerView(null, price, filters,
                headers(request, sortKey, ascending, filters.amountHeader(), pageSize),
                totals(filtered, bases, results, balances, books, market, all, range, skipped),
                rows, pager(request, sorted.size(), page, pages, sortKey,
                        ascending ? "asc" : "desc"), raw,
                rawRows, pager(request, rawFiltered.size(), rawPage, rawPages, null, null),
                pairTotals, rawTotals);
    }

    // ---- the account ------------------------------------------------------------------------

    private String market(LedgerRequest request) {
        if (notBlank(request.getMarket())) {
            if (ALL.equalsIgnoreCase(request.getMarket().trim())) {
                return ALL;
            }
            try {
                // Markets end up in the signed API path and in every generated link, so only a
                // well-formed EUR pair is accepted; anything else falls back to the engine's market.
                String selected = MarketFormat.toMarket(request.getMarket());
                if (MarketFormat.isEuroQuoted(selected)) {
                    return selected;
                }
                log.warn("Ignoring an unsupported market filter: {}", request.getMarket());
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring an unsupported market filter: {}", request.getMarket());
            }
        }
        return liveStrategyService.all().stream()
                .filter(strategy -> MarketFormat.isEuroQuoted(strategy.getMarket()))
                .max(Comparator.comparing(LiveStrategy::getCreatedTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(LiveStrategy::getMarket)
                .orElseGet(() -> marketService.selectable().stream().findFirst()
                        .orElse(DEFAULT_MARKET));
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
        List<String> known = new ArrayList<>();
        known.add(ALL);
        known.addAll(marketService.selectable());
        if (!known.contains(market)) {
            known.add(market);
        }
        return known.stream()
                .map(value -> new Option(value, ALL.equals(value) ? "All pairs" : value))
                .toList();
    }

    // ---- reads ------------------------------------------------------------------------------

    private List<OrderResponse> orders(BitvavoCredentials credentials, List<String> markets,
                                       HistoryPeriods.Range range, List<String> skipped) {
        // The exchange has no all-markets order read — it requires one market per call — so the
        // all-pairs view merges the catalogue's markets and whatever a live strategy trades. A
        // pair the exchange refuses is left out with a note instead of taking the page down.
        List<OrderResponse> all = new ArrayList<>();
        for (String market : markets) {
            try {
                all.addAll(ordersFor(credentials, market, range));
            } catch (RestClientException e) {
                if (markets.size() == 1) {
                    // One market is the page's whole read: keep the fatal message the page shows
                    // when the account cannot be read at all.
                    throw e;
                }
                log.warn("Could not read the {} orders: {}", market, e.getMessage());
                skipped.add(market);
            }
        }
        return all;
    }

    private List<OrderResponse> ordersFor(BitvavoCredentials credentials, String market,
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
     * The markets the all-pairs view reads: the same set the pickers offer — the enabled catalogue
     * plus every market a live strategy trades — so a hidden pair does not add a call. Bitvavo has
     * no all-markets order read, so this is one signed call per pair; the catalogue is a handful of
     * pairs by design, and the ledger is already a refreshed view.
     */
    private List<String> ledgerMarkets() {
        Set<String> markets = new TreeSet<>();
        for (String market : marketService.selectable()) {
            if (MarketFormat.isEuroQuoted(market)) {
                markets.add(market);
            }
        }
        for (LiveStrategy strategy : liveStrategyService.all()) {
            if (MarketFormat.isEuroQuoted(strategy.getMarket())) {
                markets.add(strategy.getMarket());
            }
        }
        markets.add(DEFAULT_MARKET);
        return new ArrayList<>(markets);
    }

    /**
     * The account's whole buy/sell history grouped by the currency on each side of the trade. A
     * base currency can move on either side — a buy of another asset paid with bitcoin spends
     * bitcoin — so a transaction joins both currencies' lists, whatever the trade's quote. The
     * balance walks use every movement; the euro cost walk filters to entries the history prices
     * in euro (see {@link #bases}).
     */
    private Map<String, List<Transaction>> fillsByBase(BitvavoCredentials credentials,
                                                       long toMillis) {
        Map<String, List<Transaction>> byBase = new TreeMap<>();
        for (String type : List.of("buy", "sell")) {
            for (Transaction entry : history(credentials, type, GENESIS_MILLIS, toMillis)) {
                addFill(byBase, entry.getReceivedCurrency(), entry);
                addFill(byBase, entry.getSentCurrency(), entry);
            }
        }
        for (List<Transaction> fills : byBase.values()) {
            fills.sort(Comparator.comparing(Transaction::getExecutedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
        }
        return byBase;
    }

    private static void addFill(Map<String, List<Transaction>> byBase, String currency,
                                Transaction entry) {
        if (currency != null) {
            byBase.computeIfAbsent(currency, key -> new ArrayList<>()).add(entry);
        }
    }

    private static Map<String, Basis> bases(Map<String, List<Transaction>> fills,
                                            List<String> markets) {
        Map<String, Basis> bases = new HashMap<>();
        for (String market : markets) {
            String base = base(market);
            // The euro cost walk can only price entries whose history reports a euro price. A
            // movement quoted in another currency still moves the balance, so it stays in the
            // balance walk and is left out of the average cost instead of being valued wrongly.
            List<Transaction> priced = fills.getOrDefault(base, List.of()).stream()
                    .filter(entry -> EUR.equalsIgnoreCase(entry.getPriceCurrency()))
                    .toList();
            bases.put(market, Basis.walk(priced, EUR, base));
        }
        return bases;
    }

    private static Basis basisFor(OrderResponse order, String selectedMarket,
                                  Map<String, Basis> bases) {
        String market = marketOf(order, selectedMarket);
        return bases.getOrDefault(market, Basis.walk(List.of(), EUR, base(market)));
    }

    /**
     * @param order          The order
     * @param selectedMarket The market the page selected, or {@code all}
     * @return The order's own market, with the selected market as the fallback for an order that
     *         carries none (and a real symbol even in the all-pairs view)
     */
    private static String marketOf(OrderResponse order, String selectedMarket) {
        String market = order.getMarket() == null || ALL.equals(order.getMarket())
                ? selectedMarket : order.getMarket();
        return ALL.equals(market) ? DEFAULT_MARKET : market;
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
     * The account's balance of every currency, with a way to ask what a base was after a given
     * fill: the walk counts fills backwards from today's balance, so "Balance after" counts only
     * filled orders. One read serves every pair in the all-pairs view.
     */
    private Map<String, Balance> balances(BitvavoCredentials credentials,
                                          Map<String, List<Transaction>> fills) {
        GetAccountBalanceResponse[] response = bitvavoApiClient.get("/balance",
                GetAccountBalanceResponse[].class, credentials.getApiKey(), credentials.getApiSecret());
        Map<String, Double> current = new HashMap<>();
        if (response != null) {
            for (GetAccountBalanceResponse entry : response) {
                current.merge(entry.getSymbol(),
                        number(entry.getAvailable()) + number(entry.getInOrder()), Double::sum);
            }
        }
        Set<String> bases = new HashSet<>(current.keySet());
        bases.addAll(fills.keySet());
        Map<String, Balance> balances = new HashMap<>();
        for (String base : bases) {
            List<Transaction> entries = fills.getOrDefault(base, List.of());
            double amount = current.getOrDefault(base, 0.0);
            NavigableMap<Long, Double> afterByFill = new TreeMap<>();
            double running = amount
                    - entries.stream().mapToDouble(fill -> baseDelta(fill, base)).sum();
            for (Transaction fill : entries) {
                running += baseDelta(fill, base);
                if (fill.getExecutedAt() != null) {
                    afterByFill.put(fill.getExecutedAt().toEpochMilli(), running);
                }
            }
            balances.put(base, new Balance(amount, afterByFill));
        }
        return balances;
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

    /**
     * @return Every market's book, read in one call, for the all-pairs view's current prices
     */
    private Map<String, GetBookResponse> books() {
        try {
            GetBookResponse[] response = bitvavoApiClient.getPublic("/ticker/book",
                    GetBookResponse[].class);
            Map<String, GetBookResponse> books = new HashMap<>();
            if (response != null) {
                for (GetBookResponse book : response) {
                    if (book.getMarket() != null) {
                        books.put(book.getMarket(), book);
                    }
                }
            }
            return books;
        } catch (RestClientException e) {
            log.warn("Could not read the current prices: {}", e.getMessage());
            return Map.of();
        }
    }

    private static double bid(GetBookResponse book) {
        return book == null || book.getBid() == null ? Double.NaN : book.getBid().doubleValue();
    }

    // ---- the view ---------------------------------------------------------------------------

    private OrderRowView row(OrderResponse order, int number, Map<String, Balance> balances,
                             String selectedMarket, double result) {
        String market = marketOf(order, selectedMarket);
        Balance balance = balances.getOrDefault(base(market), new Balance(0, new TreeMap<>()));
        boolean buy = isBuy(order);
        double amount = number(order.getFilledAmount());
        double quoteAmount = number(order.getFilledAmountQuote());
        double cashFee = cashFee(order, market);
        // The average of the fills, so it is a dash until something filled; what a waiting order
        // carries is named under its status instead.
        String avgPrice = amount > 0 ? euro(quoteAmount / amount) : DASH;
        double netCash = buy ? -(quoteAmount + cashFee) : quoteAmount - cashFee;
        return new OrderRowView(number,
                order.getCreated() == null ? DASH : display.minuteYear(Instant.ofEpochMilli(order.getCreated())),
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
                    entry.getExecutedAt() == null ? DASH : display.minuteYear(entry.getExecutedAt()),
                    type, typeClass(type),
                    amountWithCurrency(entry.getSentAmount(), entry.getSentCurrency()),
                    amountWithCurrency(entry.getReceivedAmount(), entry.getReceivedCurrency()),
                    amountWithCurrency(entry.getFeesAmount(), entry.getFeesCurrency()),
                    entry.getAddress() == null || entry.getAddress().isBlank()
                            ? DASH : entry.getAddress()));
        }
        return rows;
    }

    private FiltersView filters(LedgerRequest request, String market, boolean raw) {
        return filters(request, market, raw, List.of());
    }

    /**
     * @param rawEntries The period's raw transactions, when the raw view is selected: the exchange
     *                   returns transaction types the published list does not name (a margin
     *                   collateral deposit, for example), and those have to be filterable too
     */
    private FiltersView filters(LedgerRequest request, String market, boolean raw,
                                List<Transaction> rawEntries) {
        boolean custom = HistoryPeriods.usesCustomRange(request.getPeriod(), request.getFromDate(),
                request.getToDate());
        List<Option> typeOptions = new ArrayList<>(raw ? RAW_TYPE_OPTIONS : ORDER_TYPE_OPTIONS);
        if (raw) {
            Set<String> known = RAW_TYPE_OPTIONS.stream().map(Option::value)
                    .collect(Collectors.toSet());
            rawEntries.stream().map(Transaction::getType)
                    .filter(type -> type != null && !known.contains(type))
                    .distinct().sorted()
                    .forEach(type -> typeOptions.add(new Option(type, rawTypeLabel(type))));
        }
        List<Option> statusOptions = raw ? List.of() : STATUS_OPTIONS;
        return new FiltersView(request.getSide(), market, amountHeader(market),
                selected(request.getType(), typeOptions.stream().map(Option::value).toList()),
                selected(request.getStatus(), statusOptions.stream().map(Option::value).toList()),
                raw ? RAW_VIEW : DEFAULT_VIEW, sortKey(request.getSort()),
                "asc".equalsIgnoreCase(request.getDir()) ? "asc" : "desc",
                custom ? "" : HistoryPeriods.preset(request.getPeriod()),
                custom ? request.getFromDate() : null,
                HistoryPeriods.shownFromTime(custom ? request.getFromTime() : null),
                custom ? request.getToDate() : null,
                HistoryPeriods.shownToTime(custom ? request.getToTime() : null),
                HistoryPeriods.DEFAULT_PRESET, marketOptions(market),
                typeOptions, statusOptions, HistoryPeriods.OPTIONS);
    }

    private static String amountHeader(String market) {
        return ALL.equals(market) ? "Amount" : "Amount (" + base(market) + ")";
    }

    // ---- sorting the order table ------------------------------------------------------------

    private static String sortKey(String sort) {
        return switch (sort == null ? "" : sort) {
            case "side", "type", "market", "amount", "price", "status", "balance", "fee",
                 "netCash", "fills", "pnl" -> sort;
            default -> DEFAULT_SORT;
        };
    }

    private List<HeaderView> headers(LedgerRequest request, String sort, boolean ascending,
                                     String amountHeader, int size) {
        List<HeaderView> headers = new ArrayList<>();
        headers.add(header("Order date", "left", "date", request, sort, ascending, size));
        headers.add(header("Side", "left", "side", request, sort, ascending, size));
        headers.add(header("Type", "left", "type", request, sort, ascending, size));
        headers.add(header("Market", "left", "market", request, sort, ascending, size));
        headers.add(header(amountHeader, "", "amount", request, sort, ascending, size));
        headers.add(header("Avg price", "", "price", request, sort, ascending, size));
        headers.add(header("Status", "left", "status", request, sort, ascending, size));
        headers.add(header("Balance after", "", "balance", request, sort, ascending, size));
        headers.add(header("Total fee", "", "fee", request, sort, ascending, size));
        headers.add(header("Net cash", "", "netCash", request, sort, ascending, size));
        headers.add(header("Fills", "", "fills", request, sort, ascending, size));
        headers.add(header("P&L", "", "pnl", request, sort, ascending, size));
        return headers;
    }

    private HeaderView header(String label, String alignClass, String key, LedgerRequest request,
                              String sort, boolean ascending, int size) {
        boolean active = key.equals(sort);
        String direction = active ? (ascending ? "desc" : "asc") : "asc";
        return new HeaderView(label, alignClass, query(request, key, direction, 1, size),
                active, active ? (ascending ? "▲" : "▼") : "");
    }

    /**
     * The per-order realised results, computed once: the P&amp;L sort, the summary and the
     * per-pair totals all read this instead of walking each order's fills three times.
     */
    private Map<OrderResponse, Double> results(List<OrderResponse> orders, Map<String, Basis> bases,
                                               String selectedMarket) {
        Map<OrderResponse, Double> results = new IdentityHashMap<>();
        for (OrderResponse order : orders) {
            results.put(order, result(order, basisFor(order, selectedMarket, bases)));
        }
        return results;
    }

    /**
     * The euro part of each order's fee, computed once for the fee sort and the per-pair totals.
     */
    private Map<OrderResponse, Double> euroFees(List<OrderResponse> orders,
                                                String selectedMarket) {
        Map<OrderResponse, Double> fees = new IdentityHashMap<>();
        for (OrderResponse order : orders) {
            fees.put(order, euroFee(order, marketOf(order, selectedMarket)));
        }
        return fees;
    }

    /**
     * Sorts the filtered orders on one key, computed once per order. A value an order does not
     * have sorts last in both directions; equal keys keep the newest order first.
     */
    private List<OrderResponse> sorted(List<OrderResponse> orders, LedgerRequest request,
                                       Map<String, Balance> balances, String selectedMarket,
                                       Map<OrderResponse, Double> results,
                                       Map<OrderResponse, Double> euroFees) {
        String sort = sortKey(request.getSort());
        boolean ascending = "asc".equalsIgnoreCase(request.getDir());
        Comparator<OrderResponse> tie = Comparator.comparingLong(LedgerService::created).reversed();
        if (isTextSort(sort)) {
            Map<OrderResponse, String> keys = new IdentityHashMap<>();
            for (OrderResponse order : orders) {
                keys.put(order, textKey(sort, order));
            }
            Comparator<String> order = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
            Comparator<OrderResponse> comparator = Comparator.comparing(
                    (OrderResponse entry) -> keys.get(entry), Comparator.nullsLast(order));
            return orders.stream().sorted(comparator.thenComparing(tie)).toList();
        }
        Map<OrderResponse, Double> keys = new IdentityHashMap<>();
        for (OrderResponse order : orders) {
            keys.put(order, numberKey(sort, order, balances, selectedMarket, results, euroFees));
        }
        Comparator<Double> order = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        Comparator<OrderResponse> comparator = Comparator.comparing(
                (OrderResponse entry) -> keys.get(entry), Comparator.nullsLast(order));
        return orders.stream().sorted(comparator.thenComparing(tie)).toList();
    }

    private static boolean isTextSort(String sort) {
        return "side".equals(sort) || "type".equals(sort) || "market".equals(sort)
                || "status".equals(sort);
    }

    private static String textKey(String sort, OrderResponse order) {
        return switch (sort) {
            case "side" -> order.getSide();
            case "type" -> order.getOrderType();
            case "market" -> order.getMarket();
            default -> order.getStatus();
        };
    }

    private Double numberKey(String sort, OrderResponse order,
                                    Map<String, Balance> balances, String selectedMarket,
                                    Map<OrderResponse, Double> results,
                                    Map<OrderResponse, Double> euroFees) {
        double value = switch (sort) {
            case "amount" -> amountValue(order);
            case "price" -> averagePrice(order);
            case "balance" -> balanceAfter(order, balances, selectedMarket);
            case "fee" -> euroFees.getOrDefault(order, 0.0);
            case "netCash" -> netCashValue(order, selectedMarket);
            case "fills" -> order.getFills() == null ? 0 : order.getFills().size();
            case "pnl" -> results.getOrDefault(order, Double.NaN);
            default -> created(order);
        };
        return Double.isFinite(value) ? value : null;
    }

    private static double amountValue(OrderResponse order) {
        if (order.getAmount() != null) {
            return number(order.getAmount());
        }
        return order.getAmountQuote() != null ? number(order.getAmountQuote()) : Double.NaN;
    }

    private static double averagePrice(OrderResponse order) {
        double amount = number(order.getFilledAmount());
        return amount > 0 ? number(order.getFilledAmountQuote()) / amount : Double.NaN;
    }

    private static double balanceAfter(OrderResponse order, Map<String, Balance> balances,
                                       String selectedMarket) {
        Balance balance = balances.get(base(marketOf(order, selectedMarket)));
        return balance == null ? Double.NaN : balance.afterValue(order);
    }

    private double netCashValue(OrderResponse order, String selectedMarket) {
        double amount = number(order.getFilledAmount());
        double quote = number(order.getFilledAmountQuote());
        if (amount <= 0 && quote <= 0) {
            return Double.NaN;
        }
        double fee = cashFee(order, marketOf(order, selectedMarket));
        return isBuy(order) ? -(quote + fee) : quote - fee;
    }

    private double euroFee(OrderResponse order, String market) {
        double total = 0;
        for (Fee fee : feeParts(order, market)) {
            if (EUR.equals(fee.currency())) {
                total += fee.amount();
            }
        }
        return total;
    }

    // ---- totals by pair and by raw type ------------------------------------------------------

    private List<PairTotalView> pairTotals(List<OrderResponse> orders,
                                           Map<OrderResponse, Double> results,
                                           Map<OrderResponse, Double> euroFees,
                                           String selectedMarket) {
        Map<String, PairTotal> totals = new TreeMap<>();
        for (OrderResponse order : orders) {
            String market = marketOf(order, selectedMarket);
            PairTotal total = totals.computeIfAbsent(market, PairTotal::new);
            total.orders++;
            double quote = number(order.getFilledAmountQuote());
            if (isBuy(order)) {
                total.buyVolume += quote;
            } else {
                total.sellVolume += quote;
            }
            total.fees += euroFees.getOrDefault(order, 0.0);
            double result = results.getOrDefault(order, Double.NaN);
            if (Double.isFinite(result)) {
                total.realised += result;
            }
        }
        return totals.values().stream().map(PairTotal::view).toList();
    }

    private List<RawTotalView> rawTotals(List<Transaction> entries) {
        Map<String, RawTotal> totals = new TreeMap<>();
        for (Transaction entry : entries) {
            String currency = assetCurrency(entry);
            if (currency == null || entry.getType() == null) {
                continue;
            }
            String key = entry.getType() + "|" + currency;
            RawTotal total = totals.computeIfAbsent(key,
                    ignored -> new RawTotal(entry.getType(), currency));
            total.transactions++;
            total.amount += assetAmount(entry, currency);
        }
        return totals.values().stream().map(RawTotal::view).toList();
    }

    /**
     * @return The currency a transaction moved: the non-euro side, or euro for a euro-only transfer
     *         (a fiat deposit); null when the entry names neither side
     */
    private static String assetCurrency(Transaction entry) {
        String received = entry.getReceivedCurrency();
        String sent = entry.getSentCurrency();
        if (notBlank(received) && !EUR.equalsIgnoreCase(received)) {
            return received;
        }
        if (notBlank(sent) && !EUR.equalsIgnoreCase(sent)) {
            return sent;
        }
        return notBlank(received) ? received : sent;
    }

    private static double assetAmount(Transaction entry, String currency) {
        return currency.equalsIgnoreCase(entry.getReceivedCurrency())
                ? number(entry.getReceivedAmount()) : number(entry.getSentAmount());
    }

    private static String rawTypeLabel(String type) {
        return RAW_TYPE_OPTIONS.stream()
                .filter(option -> option.value().equalsIgnoreCase(type))
                .map(Option::label)
                .findFirst()
                .orElseGet(() -> prettyType(type));
    }

    /**
     * @param type An undocumented API type, e.g. {@code margin_loan_collateral_deposit}
     * @return The type with its underscores read as spaces, e.g. {@code Margin loan collateral deposit}
     */
    private static String prettyType(String type) {
        String text = type.replace('_', ' ').trim();
        return text.isEmpty() ? type
                : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static List<Transaction> filterRaw(List<Transaction> entries, List<String> types) {
        if (types.isEmpty()) {
            return entries;
        }
        return entries.stream()
                .filter(entry -> types.stream()
                        .anyMatch(type -> type.equalsIgnoreCase(entry.getType())))
                .toList();
    }

    private TotalsView totals(List<OrderResponse> orders, Map<String, Basis> bases,
                              Map<OrderResponse, Double> results,
                              Map<String, Balance> balances, Map<String, GetBookResponse> books,
                              String market, boolean all, HistoryPeriods.Range range,
                              List<String> skipped) {
        String feesText = feesText(orders, market);
        double realised = orders.stream()
                .mapToDouble(order -> results.getOrDefault(order, Double.NaN))
                .filter(Double::isFinite).sum();
        double unrealised = all
                ? unrealisedAll(bases, books, range)
                : unrealised(bases.getOrDefault(market, Basis.walk(List.of(), EUR, base(market))),
                        bid(books.get(market)), range);
        String balanceText = all
                ? euro(balances.getOrDefault(EUR, new Balance(0, new TreeMap<>())).current())
                : plain(balances.getOrDefault(base(market), new Balance(0, new TreeMap<>())).current())
                        + " " + base(market);
        String note = skipped.isEmpty() ? ""
                : "Could not read the orders of " + String.join(", ", skipped)
                        + "; the totals leave them out.";
        return new TotalsView(range.label(), orders.size(), feesText,
                signed(realised), signClass(realised),
                Double.isNaN(unrealised) ? DASH : signed(unrealised),
                Double.isNaN(unrealised) ? "" : signClass(unrealised),
                balanceText, unrealisedHint(bases, books, market, all, range), note);
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

    /**
     * The all-pairs counterpart: every held coin at its own market's bid against what its average
     * cost walk says it cost. One missing price makes the total a dash, because a partial sum would
     * read as a smaller position rather than as an unavailable figure.
     */
    private double unrealisedAll(Map<String, Basis> bases, Map<String, GetBookResponse> books,
                                 HistoryPeriods.Range range) {
        if (!endsToday(range)) {
            return Double.NaN;
        }
        double total = 0;
        boolean held = false;
        for (Map.Entry<String, Basis> entry : bases.entrySet()) {
            Basis basis = entry.getValue();
            if (basis.quantity() <= 0) {
                continue;
            }
            double bid = bid(books.get(entry.getKey()));
            if (!Double.isFinite(bid)) {
                return Double.NaN;
            }
            held = true;
            total += basis.quantity() * (bid - basis.averageCost());
        }
        return held ? total : Double.NaN;
    }

    private boolean endsToday(HistoryPeriods.Range range) {
        // Both sides read in the display zone — the range's end date and what the clock calls today —
        // so a range that ends at now is "today" from 00:00 local on, as HistoryPeriods resolves it.
        LocalDate end = Instant.ofEpochMilli(range.toMillis()).atZone(display.zone()).toLocalDate();
        return end.equals(clock.instant().atZone(display.zone()).toLocalDate());
    }

    private String unrealisedHint(Map<String, Basis> bases, Map<String, GetBookResponse> books,
                                  String market, boolean all, HistoryPeriods.Range range) {
        if (!endsToday(range)) {
            return RANGE_HINT;
        }
        boolean held = all
                ? bases.values().stream().anyMatch(basis -> basis.quantity() > 0)
                : bases.getOrDefault(market, Basis.walk(List.of(), EUR, base(market))).quantity() > 0;
        if (!held) {
            return "Nothing is held, so there is no unrealised result.";
        }
        boolean prices = all
                ? bases.entrySet().stream()
                        .filter(entry -> entry.getValue().quantity() > 0)
                        .allMatch(entry -> Double.isFinite(bid(books.get(entry.getKey()))))
                : Double.isFinite(bid(books.get(market)));
        return prices ? ""
                : "The current price could not be read, so there is no unrealised figure.";
    }

    private PagerView pager(LedgerRequest request, int total, int page, int pages,
                            String sort, String dir) {
        int size = pageSize(request.getSize());
        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(fromIndex + size, total);
        String prev = page > 1 ? query(request, sort, dir, page - 1, size) : null;
        String next = page < pages ? query(request, sort, dir, page + 1, size) : null;
        List<SizeOption> sizes = PAGE_SIZES.stream()
                .map(candidate -> new SizeOption(String.valueOf(candidate),
                        query(request, sort, dir, 1, candidate), candidate == size))
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
            return plain(afterValue(order));
        }

        /**
         * @param order The order
         * @return The base-currency balance after the order's fills (or after the fills that
         *         preceded it), or today's balance when nothing preceded it
         */
        double afterValue(OrderResponse order) {
            Map.Entry<Long, Double> entry = order.getFills() == null || order.getFills().isEmpty()
                    ? afterByFill.floorEntry(created(order))
                    : lastFillAfter(order);
            return entry == null ? current : entry.getValue();
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
     * The mutable accumulator behind one row of the per-pair totals.
     */
    private static final class PairTotal {

        private final String market;
        private int orders;
        private double buyVolume;
        private double sellVolume;
        private double fees;
        private double realised;

        private PairTotal(String market) {
            this.market = market;
        }

        private PairTotalView view() {
            return new PairTotalView(market, orders, euro(buyVolume), euro(sellVolume), euro(fees),
                    signed(realised), signClass(realised));
        }
    }

    /**
     * The mutable accumulator behind one row of the raw-type totals.
     */
    private static final class RawTotal {

        private final String type;
        private final String currency;
        private int transactions;
        private double amount;

        private RawTotal(String type, String currency) {
            this.type = type;
            this.currency = currency;
        }

        private RawTotalView view() {
            return new RawTotalView(rawTypeLabel(type), currency, money(amount, currency),
                    transactions);
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
            String orderMarket = marketOf(order, market);
            for (Fee fee : feeParts(order, orderMarket)) {
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

    private String query(LedgerRequest request, String sort, String dir, int page, int size) {
        List<String> pairs = new ArrayList<>();
        pairs.add("tab=ledger");
        add(pairs, "side", request.getSide());
        add(pairs, "market", request.getMarket());
        addAll(pairs, "type", request.getType());
        addAll(pairs, "status", request.getStatus());
        add(pairs, "view", request.getView());
        add(pairs, "sort", sort);
        add(pairs, "dir", dir);
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
