package nl.jimkaplan.autotrader.service;

import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountHistoryResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetMarketResponse;
import nl.jimkaplan.autotrader.bitvavo.model.Transaction;
import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.model.document.Market;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.repository.MarketRepository;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The catalogue's rules: only EUR pairs, only markets Bitvavo knows and still trades, discovery
 * from the account's own history, and a disabled pair whose facts a later discovery refreshes
 * without being re-enabled.
 */
@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Mock
    private MarketRepository markets;
    @Mock
    private LiveStrategyRepository strategies;
    @Mock
    private BitvavoApiClient client;
    @Mock
    private BitvavoCredentialsService credentials;

    private MarketService service;

    @BeforeEach
    void setUp() {
        service = new MarketService(markets, strategies, client, credentials,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- adding a pair ----------------------------------------------------------------------

    @Test
    void addStoresTheFactsBitvavoReports() {
        when(client.getPublic("/markets?market=ETH-EUR", GetMarketResponse.class))
                .thenReturn(info("ETH-EUR", "trading"));
        when(markets.save(any(Market.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Market added = service.add("etheur");

        assertEquals("ETH-EUR", added.getMarket());
        assertEquals("ETH", added.getBase());
        assertEquals("EUR", added.getQuote());
        assertEquals("trading", added.getStatus());
        assertEquals(Market.SOURCE_MANUAL, added.getSource());
        assertEquals(NOW, added.getAddedTime());
        assertTrue(added.isEnabled());
        verify(markets).save(added);
    }

    @Test
    void addRejectsANonEurPairBeforeAskingBitvavo() {
        assertThrows(IllegalArgumentException.class, () -> service.add("BTCUSDT"));

        verify(client, never()).getPublic(anyString(), eq(GetMarketResponse.class));
        verify(markets, never()).save(any());
    }

    @Test
    void addRejectsAPairAlreadyInTheCatalogue() {
        when(markets.existsByMarket("ETH-EUR")).thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.add("ETH-EUR"));

        assertTrue(error.getMessage().contains("already in the trading pairs"));
        verify(client, never()).getPublic(anyString(), eq(GetMarketResponse.class));
    }

    @Test
    void addTurnsAnUnknownMarketIntoAClientError() {
        when(client.getPublic("/markets?market=NOPE-EUR", GetMarketResponse.class))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.add("NOPE-EUR"));

        assertEquals("Unknown market: NOPE-EUR", error.getMessage());
    }

    @Test
    void addDoesNotHideAnExchangeFailure() {
        when(client.getPublic("/markets?market=ETH-EUR", GetMarketResponse.class))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        assertThrows(HttpClientErrorException.class, () -> service.add("ETH-EUR"));
    }

    @Test
    void addRejectsAPairBitvavoNoLongerTrades() {
        when(client.getPublic("/markets?market=HNT-EUR", GetMarketResponse.class))
                .thenReturn(info("HNT-EUR", "halted"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.add("HNT-EUR"));

        assertTrue(error.getMessage().contains("not trading"));
        verify(markets, never()).save(any());
    }

    @Test
    void addTurnsAConcurrentDuplicateIntoAClientError() {
        when(client.getPublic("/markets?market=ETH-EUR", GetMarketResponse.class))
                .thenReturn(info("ETH-EUR", "trading"));
        when(markets.save(any(Market.class))).thenThrow(new DuplicateKeyException("duplicate"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.add("ETH-EUR"));

        assertTrue(error.getMessage().contains("already in the trading pairs"));
    }

    // ---- enabling and ordering ----------------------------------------------------------------

    @Test
    void setEnabledFlipsTheFlag() {
        Market stored = market("ETH-EUR", true, Market.SOURCE_DISCOVERED);
        when(markets.findByMarket("ETH-EUR")).thenReturn(Optional.of(stored));
        when(markets.save(stored)).thenReturn(stored);

        Market updated = service.setEnabled("eth-eur", false);

        assertFalse(updated.isEnabled());
        assertFalse(stored.isEnabled());
        verify(markets).save(stored);
    }

    @Test
    void setEnabledRejectsAnUnknownPair() {
        when(markets.findByMarket("ETH-EUR")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.setEnabled("ETH-EUR", false));
    }

    @Test
    void allListsEnabledPairsFirstAndThenByTheLastTrade() {
        Market btc = market("BTC-EUR", false, Market.SOURCE_DISCOVERED);
        btc.setLastTradedAt(Instant.parse("2026-10-09T00:00:00Z"));
        Market eth = market("ETH-EUR", true, Market.SOURCE_DISCOVERED);
        eth.setLastTradedAt(Instant.parse("2025-08-14T00:00:00Z"));
        Market ada = market("ADA-EUR", true, Market.SOURCE_DISCOVERED);
        ada.setLastTradedAt(Instant.parse("2021-11-08T00:00:00Z"));
        when(markets.findAll()).thenReturn(List.of(btc, eth, ada));

        List<Market> all = service.all();

        assertEquals(List.of("ETH-EUR", "ADA-EUR", "BTC-EUR"),
                all.stream().map(Market::getMarket).toList());
    }

    @Test
    void selectableUnionsTheEnabledCatalogueWithLiveStrategies() {
        Market btc = market("BTC-EUR", true, Market.SOURCE_DISCOVERED);
        btc.setLastTradedAt(Instant.parse("2026-10-09T00:00:00Z"));
        Market eth = market("ETH-EUR", true, Market.SOURCE_DISCOVERED);
        eth.setLastTradedAt(Instant.parse("2025-08-14T00:00:00Z"));
        Market ada = market("ADA-EUR", false, Market.SOURCE_DISCOVERED);
        ada.setLastTradedAt(Instant.parse("2021-11-08T00:00:00Z"));
        when(markets.findAll()).thenReturn(List.of(btc, eth, ada));
        // ADA is hidden in the catalogue but a strategy still trades it; SOL was never traded, and
        // a non-EUR strategy market is not offered.
        when(strategies.findAll()).thenReturn(List.of(
                LiveStrategy.builder().market("ADA-EUR").build(),
                LiveStrategy.builder().market("SOL-EUR").build(),
                LiveStrategy.builder().market("BTCUSDT").build()));

        assertEquals(List.of("BTC-EUR", "ETH-EUR", "ADA-EUR", "SOL-EUR"), service.selectable());
    }

    // ---- discovery --------------------------------------------------------------------------

    @Test
    void discoverAddsNewPairsAndRefreshesStoredOnesWithoutReEnablingThem() {
        when(credentials.get()).thenReturn(Optional.of(credentials()));
        when(client.getPublic(eq("/markets"), eq(GetMarketResponse[].class)))
                .thenReturn(new GetMarketResponse[]{info("ETH-EUR", "trading"),
                        info("BTC-EUR", "trading")});
        when(client.get(contains("type=buy"), eq(GetAccountHistoryResponse.class),
                anyString(), anyString()))
                .thenReturn(history(List.of(
                        buy("ETH", "EUR", "2024-01-01"),
                        buy("BTC", "EUR", "2026-10-01"),
                        buy("ETH", "EUR", "2026-09-01"),
                        buy("USDC", "USDT", "2026-01-01")), 1));
        when(client.get(contains("type=sell"), eq(GetAccountHistoryResponse.class),
                anyString(), anyString()))
                .thenReturn(history(List.of(sell("ETH", "EUR", "2026-09-15")), 1));
        Market btc = market("BTC-EUR", false, Market.SOURCE_MANUAL);
        when(markets.findByMarket("BTC-EUR")).thenReturn(Optional.of(btc));
        when(markets.findByMarket("ETH-EUR")).thenReturn(Optional.empty());
        when(markets.save(any(Market.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketService.Discovery discovery = service.discover();

        assertEquals(new MarketService.Discovery(2, 1, 1, 1), discovery);

        ArgumentCaptor<Market> saved = ArgumentCaptor.forClass(Market.class);
        verify(markets, times(2)).save(saved.capture());
        Market eth = saved.getAllValues().stream()
                .filter(market -> "ETH-EUR".equals(market.getMarket())).findFirst().orElseThrow();
        assertEquals(3, eth.getTradeCount());
        assertEquals(Instant.parse("2024-01-01T10:00:00Z"), eth.getFirstTradedAt());
        assertEquals(Instant.parse("2026-09-15T10:00:00Z"), eth.getLastTradedAt());
        assertEquals("trading", eth.getStatus());
        assertTrue(eth.isEnabled());
        assertEquals(Market.SOURCE_DISCOVERED, eth.getSource());

        assertEquals(1, btc.getTradeCount());
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), btc.getLastTradedAt());
        assertEquals(Market.SOURCE_DISCOVERED, btc.getSource());
        assertFalse(btc.isEnabled(), "a disabled pair stays disabled across a discovery");
    }

    @Test
    void discoverKeepsTheStoredStatusWhenBitvavoCannotListItsMarkets() {
        when(credentials.get()).thenReturn(Optional.of(credentials()));
        when(client.getPublic(eq("/markets"), eq(GetMarketResponse[].class)))
                .thenThrow(new ResourceAccessException("down"));
        when(client.get(contains("type=buy"), eq(GetAccountHistoryResponse.class),
                anyString(), anyString()))
                .thenReturn(history(List.of(buy("ETH", "EUR", "2026-09-01")), 1));
        when(client.get(contains("type=sell"), eq(GetAccountHistoryResponse.class),
                anyString(), anyString()))
                .thenReturn(history(List.of(), 1));
        when(markets.findByMarket("ETH-EUR")).thenReturn(Optional.empty());
        when(markets.save(any(Market.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketService.Discovery discovery = service.discover();

        assertEquals(new MarketService.Discovery(1, 1, 0, 0), discovery);
        ArgumentCaptor<Market> saved = ArgumentCaptor.forClass(Market.class);
        verify(markets).save(saved.capture());
        assertEquals(null, saved.getValue().getStatus());
    }

    @Test
    void discoverReadsEveryPageAndAggregatesThePairAcrossThem() {
        when(credentials.get()).thenReturn(Optional.of(credentials()));
        when(client.getPublic(eq("/markets"), eq(GetMarketResponse[].class)))
                .thenReturn(new GetMarketResponse[]{info("ETH-EUR", "trading"),
                        info("BTC-EUR", "trading")});
        when(client.get(argThat(url -> url != null && url.contains("type=buy") && url.contains("page=1&")),
                eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenReturn(history(List.of(buy("eth", "EUR", "2024-01-01")), 2));
        when(client.get(argThat(url -> url != null && url.contains("type=buy") && url.contains("page=2&")),
                eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenReturn(history(List.of(
                        buy("ETH", "EUR", "2026-10-01"),
                        buy("BTC", "EUR", "2026-10-01")), 2));
        when(client.get(contains("type=sell"), eq(GetAccountHistoryResponse.class),
                anyString(), anyString()))
                .thenReturn(history(List.of(), 1));
        when(markets.findByMarket(anyString())).thenReturn(Optional.empty());
        when(markets.save(any(Market.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketService.Discovery discovery = service.discover();

        assertEquals(new MarketService.Discovery(2, 2, 0, 0), discovery);
        verify(client).get(argThat(url -> url != null && url.contains("type=buy") && url.contains("page=2&")),
                eq(GetAccountHistoryResponse.class), anyString(), anyString());
        ArgumentCaptor<Market> saved = ArgumentCaptor.forClass(Market.class);
        verify(markets, times(2)).save(saved.capture());
        Market eth = saved.getAllValues().stream()
                .filter(market -> "ETH-EUR".equals(market.getMarket())).findFirst().orElseThrow();
        assertEquals(2, eth.getTradeCount());
        assertEquals(Instant.parse("2024-01-01T10:00:00Z"), eth.getFirstTradedAt());
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), eth.getLastTradedAt());
    }

    @Test
    void discoverStopsAtTheHistoryPageCap() {
        when(credentials.get()).thenReturn(Optional.of(credentials()));
        when(client.getPublic(eq("/markets"), eq(GetMarketResponse[].class)))
                .thenReturn(new GetMarketResponse[]{info("ETH-EUR", "trading")});
        when(client.get(argThat(url -> url != null && url.contains("type=buy")),
                eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenReturn(history(List.of(buy("ETH", "EUR", "2026-09-01")), 100));
        when(client.get(contains("type=sell"), eq(GetAccountHistoryResponse.class),
                anyString(), anyString()))
                .thenReturn(history(List.of(), 1));
        when(markets.findByMarket("ETH-EUR")).thenReturn(Optional.empty());
        when(markets.save(any(Market.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketService.Discovery discovery = service.discover();

        assertEquals(new MarketService.Discovery(1, 1, 0, 0), discovery);
        verify(client, times(MarketService.MAX_HISTORY_PAGES)).get(
                contains("type=buy"), eq(GetAccountHistoryResponse.class), anyString(), anyString());
    }

    @Test
    void discoverKeepsGoingWhenAnotherWriterWinsAPair() {
        when(credentials.get()).thenReturn(Optional.of(credentials()));
        when(client.getPublic(eq("/markets"), eq(GetMarketResponse[].class)))
                .thenReturn(new GetMarketResponse[]{info("ETH-EUR", "trading")});
        when(client.get(contains("type=buy"), eq(GetAccountHistoryResponse.class),
                anyString(), anyString()))
                .thenReturn(history(List.of(buy("ETH", "EUR", "2026-09-01")), 1));
        when(client.get(contains("type=sell"), eq(GetAccountHistoryResponse.class),
                anyString(), anyString()))
                .thenReturn(history(List.of(), 1));
        when(markets.findByMarket("ETH-EUR")).thenReturn(Optional.empty());
        when(markets.save(any(Market.class))).thenThrow(new DuplicateKeyException("duplicate"));

        MarketService.Discovery discovery = service.discover();

        assertEquals(new MarketService.Discovery(1, 1, 0, 0), discovery);
    }

    @Test
    void discoverRequiresTheAccountKey() {
        when(credentials.get()).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.discover());

        assertTrue(error.getMessage().contains("No Bitvavo API key"));
        verify(client, never()).get(anyString(), eq(GetAccountHistoryResponse.class),
                anyString(), anyString());
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static Market market(String symbol, boolean enabled, String source) {
        return Market.builder()
                .market(symbol)
                .base(symbol.substring(0, symbol.indexOf('-')))
                .quote("EUR")
                .enabled(enabled)
                .source(source)
                .build();
    }

    private static GetMarketResponse info(String symbol, String status) {
        GetMarketResponse info = new GetMarketResponse();
        info.setMarket(symbol);
        info.setBase(symbol.substring(0, symbol.indexOf('-')));
        info.setQuote("EUR");
        info.setStatus(status);
        return info;
    }

    private static GetAccountHistoryResponse history(List<Transaction> items, int totalPages) {
        return new GetAccountHistoryResponse(items, 1, totalPages, MarketService.HISTORY_PAGE_SIZE);
    }

    private static BitvavoCredentials credentials() {
        return BitvavoCredentials.builder().apiKey("key").apiSecret("secret").build();
    }

    private static Transaction buy(String base, String quote, String day) {
        Transaction transaction = new Transaction();
        transaction.setType("buy");
        transaction.setExecutedAt(Instant.parse(day + "T10:00:00Z"));
        transaction.setPriceCurrency(quote);
        transaction.setPriceAmount(new BigDecimal("100"));
        transaction.setSentCurrency(quote);
        transaction.setSentAmount(new BigDecimal("100"));
        transaction.setReceivedCurrency(base);
        transaction.setReceivedAmount(new BigDecimal("1"));
        return transaction;
    }

    private static Transaction sell(String base, String quote, String day) {
        Transaction transaction = new Transaction();
        transaction.setType("sell");
        transaction.setExecutedAt(Instant.parse(day + "T10:00:00Z"));
        transaction.setPriceCurrency(quote);
        transaction.setPriceAmount(new BigDecimal("100"));
        transaction.setSentCurrency(base);
        transaction.setSentAmount(new BigDecimal("1"));
        transaction.setReceivedCurrency(quote);
        transaction.setReceivedAmount(new BigDecimal("100"));
        return transaction;
    }
}
