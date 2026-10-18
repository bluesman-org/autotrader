package nl.jimkaplan.autotrader.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.MarketFormat;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountHistoryResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetMarketResponse;
import nl.jimkaplan.autotrader.bitvavo.model.Transaction;
import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.model.document.Market;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.repository.MarketRepository;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

/**
 * The trading-pair catalogue: the pairs the strategy forms and the account ledger offer.
 *
 * <p>The catalogue is seeded from the account's own history, not from Bitvavo's full offering:
 * {@link #discover()} reads every buy and sell the account made and turns the base currency and
 * its price currency into a market. That is a few account-history calls rather than one call per
 * market on the exchange. A pair can also be added by hand for an asset that arrived through a
 * deposit, which has no buy in the history.</p>
 *
 * <p>A pair is disabled, never deleted: a later discovery would bring a traded pair back, and a
 * live strategy may still name it. {@link #selectable()} is the set both UIs read — the enabled
 * catalogue plus whatever a live strategy trades — so an API-created strategy on a pair nobody
 * bought through the UI stays visible.</p>
 */
@Slf4j
@Service
public class MarketService {

    /**
     * The account did not exist before Bitvavo did, so a discovery starts here rather than at an
     * arbitrary look-back. The date window matters: without it the exchange returns only a recent
     * slice of the account history.
     */
    static final long GENESIS_MILLIS = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();

    /** The API's maximum for one account-history page. */
    static final int HISTORY_PAGE_SIZE = 100;

    /**
     * A discovery that needs more pages than this stops with a warning instead of paging forever:
     * roughly this many buys (or sells) is already far beyond the pairs this catalogue serves.
     */
    static final int MAX_HISTORY_PAGES = 25;

    static final String EUR = "EUR";
    static final String TRADING = "trading";

    private final MarketRepository marketRepository;
    private final LiveStrategyRepository liveStrategyRepository;
    private final BitvavoApiClient bitvavoApiClient;
    private final BitvavoCredentialsService credentialsService;
    private final Clock clock;

    @Autowired
    public MarketService(MarketRepository marketRepository,
                         LiveStrategyRepository liveStrategyRepository,
                         BitvavoApiClient bitvavoApiClient,
                         BitvavoCredentialsService credentialsService) {
        this(marketRepository, liveStrategyRepository, bitvavoApiClient, credentialsService,
                Clock.systemUTC());
    }

    MarketService(MarketRepository marketRepository,
                  LiveStrategyRepository liveStrategyRepository,
                  BitvavoApiClient bitvavoApiClient,
                  BitvavoCredentialsService credentialsService,
                  Clock clock) {
        this.marketRepository = marketRepository;
        this.liveStrategyRepository = liveStrategyRepository;
        this.bitvavoApiClient = bitvavoApiClient;
        this.credentialsService = credentialsService;
        this.clock = clock;
    }

    /**
     * @return Every pair in the catalogue, enabled first, then by most recent trade and symbol
     */
    public List<Market> all() {
        return marketRepository.findAll().stream()
                .sorted(Comparator
                        .comparing(Market::isEnabled, Comparator.reverseOrder())
                        .thenComparing(Market::getLastTradedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Market::getMarket,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * The markets both UIs offer: the enabled catalogue plus every market a live strategy trades,
     * ordered by the account's most recent trade (a strategy-only market last), then by symbol.
     *
     * @return Well-formed market symbols
     */
    public List<String> selectable() {
        Map<String, Instant> lastTraded = new HashMap<>();
        for (Market market : all()) {
            if (market.isEnabled()) {
                lastTraded.put(market.getMarket(), market.getLastTradedAt());
            }
        }
        for (LiveStrategy strategy : liveStrategyRepository.findAll()) {
            if (strategy.getMarket() != null) {
                lastTraded.putIfAbsent(strategy.getMarket(), null);
            }
        }
        return lastTraded.keySet().stream()
                .sorted(Comparator
                        .comparing((String market) -> lastTraded.get(market),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /**
     * Add a pair to the catalogue. The pair is validated against Bitvavo's public market list
     * before it is stored: it has to exist, be EUR-quoted and be trading.
     *
     * @param input The market as typed, e.g. "eth-eur" or "ETHEUR"
     * @return The stored pair
     * @throws IllegalArgumentException if the symbol is malformed, not EUR-quoted, unknown to
     *                                  Bitvavo, not trading, or already in the catalogue
     */
    public Market add(String input) {
        String market = normalize(input);
        if (marketRepository.existsByMarket(market)) {
            throw new IllegalArgumentException(market + " is already in the trading pairs");
        }
        GetMarketResponse info = bitvavoMarket(market);
        if (info.getStatus() != null && !TRADING.equalsIgnoreCase(info.getStatus())) {
            throw new IllegalArgumentException(market + " is not trading on Bitvavo ("
                    + info.getStatus() + ")");
        }
        Market stored = marketRepository.save(Market.builder()
                .market(market)
                .base(info.getBase() == null ? baseOf(market) : info.getBase())
                .quote(EUR)
                .enabled(true)
                .source(Market.SOURCE_MANUAL)
                .status(info.getStatus())
                .tradeCount(0)
                .addedTime(clock.instant())
                .build());
        log.info("Added trading pair {}", market);
        return stored;
    }

    /**
     * Show or hide a pair in the forms and the ledger. A live strategy that trades the pair keeps
     * it visible through {@link #selectable()} regardless.
     *
     * @param input   The market symbol
     * @param enabled The new value
     * @return The updated pair
     * @throws IllegalArgumentException if the symbol is malformed or not EUR-quoted
     * @throws NoSuchElementException   if the catalogue has no such pair
     */
    public Market setEnabled(String input, boolean enabled) {
        String market = normalize(input);
        Market stored = marketRepository.findByMarket(market)
                .orElseThrow(() -> new NoSuchElementException("No such trading pair: " + market));
        stored.setEnabled(enabled);
        Market saved = marketRepository.save(stored);
        log.info("{} trading pair {}", enabled ? "Enabled" : "Disabled", market);
        return saved;
    }

    /**
     * Read the account's whole buy/sell history and add or refresh the pair of every trade. The
     * enabled flag is left alone, so a pair an operator disabled stays hidden; nothing is deleted.
     *
     * @return What the read found and changed
     * @throws IllegalArgumentException if the account's key is not configured
     * @throws RestClientException      if Bitvavo rejects or cannot answer the history read
     */
    public Discovery discover() {
        BitvavoCredentials credentials = credentialsService.get()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No Bitvavo API key is configured, so the account cannot be read. "
                                + "Store it on the settings page."));
        Map<String, GetMarketResponse> offered = offeredMarkets();
        Map<String, Traded> traded = new TreeMap<>();
        int skipped = 0;
        for (String type : List.of("buy", "sell")) {
            for (Transaction transaction : history(credentials, type)) {
                String base = "buy".equals(type)
                        ? transaction.getReceivedCurrency() : transaction.getSentCurrency();
                String quote = transaction.getPriceCurrency();
                if (base == null || quote == null || transaction.getExecutedAt() == null) {
                    continue;
                }
                if (!EUR.equalsIgnoreCase(quote)) {
                    skipped++;
                    continue;
                }
                String market = base + "-" + quote.toUpperCase(Locale.ENGLISH);
                traded.computeIfAbsent(market, Traded::new).add(transaction.getExecutedAt());
            }
        }
        int added = 0;
        int updated = 0;
        for (Traded entry : traded.values()) {
            Market stored = marketRepository.findByMarket(entry.market).orElse(null);
            GetMarketResponse info = offered.get(entry.market);
            if (stored == null) {
                stored = Market.builder()
                        .market(entry.market)
                        .enabled(true)
                        .source(Market.SOURCE_DISCOVERED)
                        .addedTime(clock.instant())
                        .build();
                added++;
            } else {
                updated++;
            }
            stored.setBase(baseOf(entry.market));
            stored.setQuote(EUR);
            stored.setSource(Market.SOURCE_DISCOVERED);
            if (info != null && info.getStatus() != null) {
                stored.setStatus(info.getStatus());
            }
            stored.setFirstTradedAt(entry.first);
            stored.setLastTradedAt(entry.last);
            stored.setTradeCount(entry.count);
            marketRepository.save(stored);
        }
        log.info("Discovered {} traded markets ({} added, {} refreshed, {} non-EUR skipped)",
                traded.size(), added, updated, skipped);
        return new Discovery(traded.size(), added, updated, skipped);
    }

    // ---- reads ------------------------------------------------------------------------------

    /**
     * Bitvavo's data for one market, read from the public endpoint so adding a pair does not need
     * a key. A market the exchange does not know is a client error, not a server failure.
     */
    private GetMarketResponse bitvavoMarket(String market) {
        try {
            GetMarketResponse info = bitvavoApiClient.getPublic("/markets?market=" + market,
                    GetMarketResponse.class);
            if (info == null || info.getMarket() == null) {
                throw new IllegalArgumentException("Unknown market: " + market);
            }
            return info;
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 400 || status == 404) {
                throw new IllegalArgumentException("Unknown market: " + market);
            }
            throw e;
        }
    }

    /**
     * @return Bitvavo's current market list by symbol. A failed read leaves the statuses alone
     *         instead of failing the discovery: the trade history is what the catalogue needs.
     */
    private Map<String, GetMarketResponse> offeredMarkets() {
        try {
            GetMarketResponse[] markets = bitvavoApiClient.getPublic("/markets",
                    GetMarketResponse[].class);
            Map<String, GetMarketResponse> byMarket = new HashMap<>();
            if (markets != null) {
                for (GetMarketResponse market : markets) {
                    if (market.getMarket() != null) {
                        byMarket.put(market.getMarket(), market);
                    }
                }
            }
            return byMarket;
        } catch (RestClientException e) {
            log.warn("Could not read Bitvavo's market list; pairs keep their stored status: {}",
                    e.getMessage());
            return Map.of();
        }
    }

    /**
     * Every buy (or sell) the account made, oldest page first. The call passes the date window on
     * purpose: without it Bitvavo returns only the recent slice of the account history.
     */
    private List<Transaction> history(BitvavoCredentials credentials, String type) {
        List<Transaction> entries = new ArrayList<>();
        for (int page = 1; page <= MAX_HISTORY_PAGES; page++) {
            String endpoint = UriComponentsBuilder.fromPath("/account/history")
                    .queryParam("fromDate", GENESIS_MILLIS)
                    .queryParam("toDate", clock.millis())
                    .queryParam("page", page)
                    .queryParam("maxItems", HISTORY_PAGE_SIZE)
                    .queryParam("type", type)
                    .build().encode().toUriString();
            GetAccountHistoryResponse response = bitvavoApiClient.get(endpoint,
                    GetAccountHistoryResponse.class, credentials.getApiKey(),
                    credentials.getApiSecret());
            if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                break;
            }
            entries.addAll(response.getItems());
            if (response.getTotalPages() == null || page >= response.getTotalPages()) {
                break;
            }
            if (page == MAX_HISTORY_PAGES) {
                log.warn("Account history read stopped at {} entries (type {}); a traded pair may "
                        + "be missing from the discovery", entries.size(), type);
            }
        }
        return entries;
    }

    // ---- small helpers ----------------------------------------------------------------------

    private static String normalize(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("The trading pair is required");
        }
        String market = MarketFormat.toMarket(input.trim().toUpperCase(Locale.ENGLISH));
        if (!market.endsWith("-" + EUR)) {
            throw new IllegalArgumentException("Only EUR-quoted markets are supported: " + market);
        }
        return market;
    }

    private static String baseOf(String market) {
        int dash = market.indexOf('-');
        return dash < 0 ? market : market.substring(0, dash);
    }

    /**
     * @param markets The number of pairs the history named
     * @param added   How many of them were new to the catalogue
     * @param updated How many were already there and had their facts refreshed
     * @param skipped How many trades were quoted in something other than euro
     */
    public record Discovery(int markets, int added, int updated, int skipped) {
    }

    /**
     * One pair's accumulation while the history is read.
     */
    private static final class Traded {

        private final String market;
        private int count;
        private Instant first;
        private Instant last;

        private Traded(String market) {
            this.market = market;
        }

        private void add(Instant when) {
            count++;
            if (first == null || when.isBefore(first)) {
                first = when;
            }
            if (last == null || when.isAfter(last)) {
                last = when;
            }
        }
    }
}
