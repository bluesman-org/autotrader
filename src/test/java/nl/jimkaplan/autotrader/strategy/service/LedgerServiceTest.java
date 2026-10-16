package nl.jimkaplan.autotrader.strategy.service;

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
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The ledger reads the real account, so everything here is a stubbed Bitvavo: a buy before the
 * period, a buy and a sell inside it and an open order, against a fixed clock and zone. The numbers
 * check the two rules that matter — a sell is measured against the average cost of the inventory
 * that was bought earlier, a buy against the current bid — and the balance walks back from today's
 * balance.
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Mock
    private BitvavoApiClient client;
    @Mock
    private BotConfigurationService bots;
    @Mock
    private LiveStrategyService strategies;

    private LedgerService service;

    @BeforeEach
    void setUp() {
        service = new LedgerService(client, bots, strategies, new DisplayZone(ZoneId.of("UTC")),
                Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    @Test
    void ordersShowFillsFeesBalanceAndTheResultAtAverageCost() {
        accountReadable();

        LedgerView view = service.view(new LedgerRequest());

        assertEquals(3, view.rows().size());
        LedgerView.OrderRowView open = view.rows().get(0);
        assertEquals("1", String.valueOf(open.number()));
        assertEquals("15 Oct 08:00", open.dateText());
        assertEquals("BUY", open.side());
        assertEquals("side-buy", open.sideClass());
        assertEquals("limit", open.typeText());
        assertEquals("0.00200000", open.amountText());
        assertEquals("€73,000.00", open.avgPriceText());
        assertEquals("open", open.statusLabel());
        assertEquals("0.0015", open.balanceAfterText());
        assertEquals("—", open.feeText());
        assertEquals("0", String.valueOf(open.fillsCount()));
        assertEquals("—", open.pnlText());

        LedgerView.OrderRowView sell = view.rows().get(1);
        assertEquals("SELL", sell.side());
        assertEquals("side-sell", sell.sideClass());
        // Sold 0.0005 of an inventory that cost €74,650.00 on average (two buys of €74.15 and
        // €75.15) at €76,000.00, minus the €0.08 fee.
        assertEquals("+€0.60", sell.pnlText());
        assertEquals("pos", sell.pnlClass());
        assertEquals("+€37.92", sell.netCashText());
        assertEquals("€0.08", sell.feeText());
        assertEquals("0.0015", sell.balanceAfterText());
        assertEquals(1, sell.fills().size());
        assertEquals("09:00:00", sell.fills().get(0).timeText());
        assertEquals("0.00050000", sell.fills().get(0).amountText());
        assertEquals("€76,000.00", sell.fills().get(0).priceText());
        assertEquals("€0.0800", sell.fills().get(0).feeText());
        assertEquals("taker", sell.fills().get(0).makerTakerText());
        assertEquals("+€37.92", sell.fills().get(0).netCashText());

        LedgerView.OrderRowView buy = view.rows().get(2);
        // Worth €75.50 at the current bid; €75.15 was paid for it.
        assertEquals("+€0.35", buy.pnlText());
        assertEquals("−€75.15", buy.netCashText());
        assertEquals("0.002", buy.balanceAfterText());
        assertEquals("filled", buy.statusLabel());
        assertEquals("ok", buy.statusClass());

        assertEquals("3", String.valueOf(view.totals().orders()));
        assertEquals("€0.23", view.totals().feesText());
        assertEquals("+€0.60", view.totals().realisedText());
        assertEquals("+€0.35", view.totals().unrealisedText());
        assertEquals("0.0015 BTC", view.totals().balanceText());
        assertEquals("Last 7 days", view.totals().periodText());
        assertEquals("BTC-EUR", view.price().market());
        assertEquals("€75,600.00", view.price().buyText());
        assertEquals("€75,500.00", view.price().sellText());
    }

    @Test
    void filtersAndPagingKeepTheLedgerState() {
        accountReadable();

        LedgerRequest sellOnly = new LedgerRequest();
        sellOnly.setSide("sell");
        assertEquals(1, service.view(sellOnly).rows().size());

        LedgerRequest openOnly = new LedgerRequest();
        openOnly.setStatus("open");
        assertEquals(1, service.view(openOnly).rows().size());

        LedgerRequest limitOnly = new LedgerRequest();
        limitOnly.setType("limit");
        assertEquals(1, service.view(limitOnly).rows().size());

        LedgerRequest paged = new LedgerRequest();
        paged.setSize(5);
        paged.setPage(1);
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{openOrder(), sellOrder(), buyOrder(),
                        olderOrder(1), olderOrder(2), olderOrder(3)});
        LedgerView first = service.view(paged);
        assertEquals(5, first.rows().size());
        assertEquals(2, first.pager().pages());
        assertTrue(first.pager().nextQuery().contains("tab=ledger"));
        assertTrue(first.pager().nextQuery().contains("page=2"));
        assertEquals(null, first.pager().prevQuery());
        paged.setPage(2);
        LedgerView second = service.view(paged);
        assertEquals(1, second.rows().size());
        assertEquals(2, second.pager().page());
        assertTrue(second.pager().prevQuery().contains("tab=ledger"));
    }

    @Test
    void theRawViewListsEveryTransactionOfThePeriod() {
        accountReadable();

        LedgerRequest request = new LedgerRequest();
        request.setView("raw");
        LedgerView view = service.view(request);

        assertTrue(view.raw());
        assertEquals(4, view.rawRows().size());
        LedgerView.RawRowView fill = view.rawRows().get(0);
        assertEquals("14 Oct 09:00", fill.timeText());
        assertEquals("sell", fill.typeText());
        assertEquals("side-sell", fill.typeClass());
        assertEquals("0.0005 BTC", fill.sentText());
        assertEquals("€38.00 EUR", fill.receivedText());
        assertEquals("€0.08 EUR", fill.feeText());
        LedgerView.RawRowView deposit = view.rawRows().get(3);
        assertEquals("deposit", deposit.typeText());
        assertEquals("", deposit.typeClass());
        assertEquals("1 Oct 00:00", deposit.timeText());
    }

    @Test
    void withoutAnAccountThePageExplainsItself() {
        when(strategies.all()).thenReturn(List.of());
        when(bots.getAllBotConfigurations()).thenReturn(List.of());
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        assertNotNull(view.message());
        assertTrue(view.message().contains("No active Bitvavo API key is configured"));
        assertEquals(0, view.rows().size());
        assertEquals(null, view.totals());
        assertEquals("BTC-EUR", view.price().market());
    }

    @Test
    void anUnreadableAccountLeavesAMessageInsteadOfData() {
        accountReadableWithoutOrders();
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenThrow(new RestClientException("403"));

        LedgerView view = service.view(new LedgerRequest());

        assertTrue(view.message().contains("Could not read the Bitvavo account"));
        assertEquals(null, view.totals());
    }

    @Test
    void aFailingPriceLookupLeavesDashesInsteadOfBreaking() {
        accountReadableWithoutOrders();
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{buyOrder()});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> history(invocation.getArgument(0)));
        when(client.getPublic(anyString(), eq(GetBookResponse.class)))
                .thenThrow(new RestClientException("book down"));

        LedgerView view = service.view(new LedgerRequest());

        assertEquals(null, view.message());
        assertEquals("—", view.price().buyText());
        assertEquals("—", view.price().sellText());
        assertEquals("—", view.rows().get(0).pnlText());
        assertEquals("+€0.00", view.totals().unrealisedText());
    }

    @Test
    void everyOrderStateHasItsOwnReading() {
        accountReadableWithoutOrders();
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{canceledOrder(), partialOrder(), baseFeeBuyOrder()});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(withAnotherSymbol());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> history(invocation.getArgument(0)));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        LedgerView.OrderRowView canceled = view.rows().get(0);
        assertEquals("canceled", canceled.statusLabel());
        assertEquals("bad", canceled.statusClass());
        assertEquals("€100.00 EUR", canceled.amountText());
        assertEquals("—", canceled.feeText());
        assertEquals("—", canceled.netCashText());
        assertEquals("—", canceled.pnlText());
        assertEquals("0.0015", canceled.balanceAfterText());

        LedgerView.OrderRowView partial = view.rows().get(1);
        assertEquals("partially filled", partial.statusLabel());
        assertEquals("warn", partial.statusClass());
        assertEquals("Filled 0.00100000 of 0.00200000 BTC · 0.00100000 remaining", partial.filledText());
        assertEquals("+€0.35", partial.pnlText());

        LedgerView.OrderRowView buyWithBaseFee = view.rows().get(2);
        assertEquals(1, buyWithBaseFee.fills().size());
        // The fee was charged in bitcoin, so it reads as bitcoin — the euro value only enters the
        // P&L, never the display.
        assertEquals("0.000002 BTC", buyWithBaseFee.fills().get(0).feeText());
        assertEquals("maker", buyWithBaseFee.fills().get(0).makerTakerText());
        // A fee taken in the coin does not move the cash, so the net cash is the euro part alone.
        assertEquals("−€75.00", buyWithBaseFee.netCashText());
        assertEquals("−€75.00", buyWithBaseFee.fills().get(0).netCashText());
        assertEquals("0.000002 BTC", buyWithBaseFee.feeText());
        // The period's fees keep each currency apart: €0.15 from the partial fill, the bitcoin fee
        // as bitcoin.
        assertEquals("€0.15 + 0.000002 BTC", view.totals().feesText());
    }

    @Test
    void aBaseCurrencyOrderFeeShowsItsOwnCurrency() {
        accountReadableWithoutOrders();
        OrderResponse order = baseOrder("2026-10-14T20:00:00Z", "buy", "limit");
        order.setStatus("canceled");
        order.setAmount(new BigDecimal("0.001"));
        order.setPrice(new BigDecimal("75000"));
        order.setFeePaid(new BigDecimal("0.000002"));
        order.setFeeCurrency("BTC");
        order.setFills(List.of());
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{order});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> new GetAccountHistoryResponse(List.of(), 1, 1, 200));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        assertEquals("0.000002 BTC", view.rows().get(0).feeText());
        assertEquals("0.000002 BTC", view.totals().feesText());
    }

    @Test
    void anUnsupportedMarketFallsBackToTheEngineMarket() {
        accountReadableWithoutOrders();
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> new GetAccountHistoryResponse(List.of(), 1, 1, 200));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerRequest request = new LedgerRequest();
        request.setMarket("BTC-EUR&limit=1");
        LedgerView view = service.view(request);

        assertEquals("BTC-EUR", view.filters().market());
        assertTrue(view.filters().marketOptions().stream()
                .noneMatch(option -> option.value().contains("&")));
        assertEquals("€75,600.00", view.price().buyText());
    }

    @Test
    void payingTheBaseCurrencyCountsItOutOfTheBalance() {
        accountReadableWithoutOrders();
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{betweenTheFills(), afterTheFills()});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances("0.0005"));
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String endpoint = invocation.getArgument(0);
                    if (endpoint.contains("type=buy")) {
                        // A buy of BTC, then a buy of ETH paid with BTC: the second moves the base
                        // currency out even though its type says buy.
                        Transaction bought = buy("2026-10-10T08:00:00Z", "0.001", "74.00", "74000", "0.15");
                        Transaction paidWithBase = buy("2026-10-11T12:00:00Z", "0.01", "750.00", "75000", "0");
                        paidWithBase.setSentCurrency("BTC");
                        paidWithBase.setSentAmount(new BigDecimal("0.0005"));
                        paidWithBase.setReceivedCurrency("ETH");
                        paidWithBase.setReceivedAmount(new BigDecimal("0.01"));
                        return new GetAccountHistoryResponse(List.of(bought, paidWithBase), 1, 1, 200);
                    }
                    return new GetAccountHistoryResponse(List.of(), 1, 1, 200);
                });
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        // The account holds 0.0005 BTC today: after the BTC buy 0.001, after paying with BTC 0.0005.
        assertEquals("0.0005", view.rows().get(0).balanceAfterText());
        assertEquals("0.001", view.rows().get(1).balanceAfterText());
    }

    @Test
    void theRawPagerClampsToOnePage() {
        accountReadable();

        LedgerRequest request = new LedgerRequest();
        request.setView("raw");
        request.setPage(3);
        LedgerView view = service.view(request);

        assertEquals(4, view.rawRows().size());
        assertEquals(1, view.rawPager().page());
        assertEquals(1, view.rawPager().from());
        assertEquals(4, view.rawPager().to());
        assertEquals(null, view.rawPager().prevQuery());
    }

    @Test
    void aSellWithoutHistoryCountsAtItsOwnPrice() {
        accountReadableWithoutOrders();
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{depositedSellOrder()});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String endpoint = invocation.getArgument(0);
                    if (endpoint.contains("type=buy")) {
                        return new GetAccountHistoryResponse(List.of(), 1, null, 200);
                    }
                    if (endpoint.contains("type=sell")) {
                        return new GetAccountHistoryResponse(null, 1, null, 200);
                    }
                    return new GetAccountHistoryResponse(List.of(), 1, 1, 200);
                });
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        // No buy to derive a cost from: the sell shows only what its fee cost.
        assertEquals("−€0.15", view.rows().get(0).pnlText());
        assertEquals("0.0015 BTC", view.totals().balanceText());
    }

    @Test
    void theAccountFallsBackToAConfiguredBotAndTheMarketToTheRequest() {
        when(strategies.all()).thenReturn(List.of());
        when(bots.getAllBotConfigurations()).thenReturn(List.of(bot()));
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> new GetAccountHistoryResponse(List.of(), 1, 1, 200));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerRequest request = new LedgerRequest();
        request.setMarket("ETH-EUR");
        LedgerView view = service.view(request);

        assertEquals(null, view.message());
        assertEquals("ETH-EUR", view.price().market());
        assertEquals("ETH-EUR", view.filters().market());
        assertEquals(1, view.filters().marketOptions().size());
        assertEquals("ETH-EUR", view.filters().marketOptions().get(0).value());
        assertEquals(0, view.rows().size());
        assertEquals("UTC", service.zoneLabel());
    }

    @Test
    void pagingDefaultsToOnePageOfTen() {
        accountReadable();

        LedgerView view = service.view(new LedgerRequest());

        assertEquals(1, view.pager().pages());
        assertEquals(10, view.pager().size());
        assertEquals(null, view.pager().prevQuery());
        assertEquals(null, view.pager().nextQuery());
        assertEquals("10", view.pager().sizes().stream()
                .filter(TradeHistoryView.SizeOption::selected).findFirst().orElseThrow().label());
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private void accountReadable() {
        accountReadableWithoutOrders();
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{openOrder(), sellOrder(), buyOrder()});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> history(invocation.getArgument(0)));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());
    }

    private void accountReadableWithoutOrders() {
        LiveStrategy strategy = org.mockito.Mockito.mock(LiveStrategy.class);
        when(strategy.getBotId()).thenReturn("bot-1");
        when(strategy.getMarket()).thenReturn("BTC-EUR");
        when(strategies.all()).thenReturn(List.of(strategy));
        when(bots.getBotConfiguration("bot-1")).thenReturn(Optional.of(bot()));
    }

    private static BotConfiguration bot() {
        BotConfiguration bot = new BotConfiguration();
        bot.setBotId("bot-1");
        bot.setApiKey("key");
        bot.setApiSecret("secret");
        return bot;
    }

    private static GetAccountHistoryResponse history(String endpoint) {
        List<Transaction> items;
        if (endpoint.contains("type=buy")) {
            items = List.of(buy("2026-10-01T00:00:00Z", "0.001", "74.00", "74000", "0.15"),
                    buy("2026-10-13T10:00:00Z", "0.001", "75.00", "75000", "0.15"));
        } else if (endpoint.contains("type=sell")) {
            items = List.of(sell("2026-10-14T09:00:00Z", "0.0005", "38.00", "76000", "0.08"));
        } else {
            items = List.of(sell("2026-10-14T09:00:00Z", "0.0005", "38.00", "76000", "0.08"),
                    buy("2026-10-13T10:00:00Z", "0.001", "75.00", "75000", "0.15"),
                    buy("2026-10-01T00:00:00Z", "0.001", "74.00", "74000", "0.15"),
                    deposit("2026-10-01T00:00:00Z", "100.00"));
        }
        return new GetAccountHistoryResponse(items, 1, 1, 200);
    }

    private static Transaction buy(String when, String amount, String paid, String price, String fee) {
        Transaction entry = new Transaction();
        entry.setType("buy");
        entry.setExecutedAt(Instant.parse(when));
        entry.setPriceCurrency("EUR");
        entry.setPriceAmount(new BigDecimal(price));
        entry.setSentCurrency("EUR");
        entry.setSentAmount(new BigDecimal(paid));
        entry.setReceivedCurrency("BTC");
        entry.setReceivedAmount(new BigDecimal(amount));
        entry.setFeesCurrency("EUR");
        entry.setFeesAmount(new BigDecimal(fee));
        return entry;
    }

    private static Transaction sell(String when, String amount, String received, String price, String fee) {
        Transaction entry = new Transaction();
        entry.setType("sell");
        entry.setExecutedAt(Instant.parse(when));
        entry.setPriceCurrency("EUR");
        entry.setPriceAmount(new BigDecimal(price));
        entry.setSentCurrency("BTC");
        entry.setSentAmount(new BigDecimal(amount));
        entry.setReceivedCurrency("EUR");
        entry.setReceivedAmount(new BigDecimal(received));
        entry.setFeesCurrency("EUR");
        entry.setFeesAmount(new BigDecimal(fee));
        return entry;
    }

    private static Transaction deposit(String when, String amount) {
        Transaction entry = new Transaction();
        entry.setType("deposit");
        entry.setExecutedAt(Instant.parse(when));
        entry.setReceivedCurrency("EUR");
        entry.setReceivedAmount(new BigDecimal(amount));
        return entry;
    }

    private static OrderResponse openOrder() {
        OrderResponse order = new OrderResponse();
        order.setOrderId(java.util.UUID.randomUUID());
        order.setMarket("BTC-EUR");
        order.setCreated(Instant.parse("2026-10-15T08:00:00Z").toEpochMilli());
        order.setStatus("new");
        order.setSide("buy");
        order.setOrderType("limit");
        order.setAmount(new BigDecimal("0.002"));
        order.setPrice(new BigDecimal("73000"));
        order.setFills(List.of());
        return order;
    }

    private static OrderResponse buyOrder() {
        OrderResponse order = new OrderResponse();
        order.setOrderId(java.util.UUID.randomUUID());
        order.setMarket("BTC-EUR");
        order.setCreated(Instant.parse("2026-10-13T10:00:00Z").toEpochMilli());
        order.setStatus("filled");
        order.setSide("buy");
        order.setOrderType("market");
        order.setAmount(new BigDecimal("0.001"));
        order.setFilledAmount(new BigDecimal("0.001"));
        order.setFilledAmountQuote(new BigDecimal("75.00"));
        order.setFeePaid(new BigDecimal("0.15"));
        order.setFeeCurrency("EUR");
        order.setFills(List.of(fill("2026-10-13T10:00:00Z", "0.001", "75000", "0.15")));
        return order;
    }

    private static OrderResponse sellOrder() {
        OrderResponse order = new OrderResponse();
        order.setOrderId(java.util.UUID.randomUUID());
        order.setMarket("BTC-EUR");
        order.setCreated(Instant.parse("2026-10-14T09:00:00Z").toEpochMilli());
        order.setStatus("filled");
        order.setSide("sell");
        order.setOrderType("market");
        order.setAmount(new BigDecimal("0.0005"));
        order.setFilledAmount(new BigDecimal("0.0005"));
        order.setFilledAmountQuote(new BigDecimal("38.00"));
        order.setFeePaid(new BigDecimal("0.08"));
        order.setFeeCurrency("EUR");
        order.setFills(List.of(fill("2026-10-14T09:00:00Z", "0.0005", "76000", "0.08")));
        return order;
    }

    private static OrderResponse olderOrder(int daysAgo) {
        OrderResponse order = new OrderResponse();
        order.setOrderId(java.util.UUID.randomUUID());
        order.setMarket("BTC-EUR");
        order.setCreated(Instant.parse("2026-10-10T08:00:00Z").minusSeconds(daysAgo * 86400L).toEpochMilli());
        order.setStatus("canceled");
        order.setSide("buy");
        order.setOrderType("limit");
        order.setAmount(new BigDecimal("0.001"));
        order.setPrice(new BigDecimal("70000"));
        order.setFills(List.of());
        return order;
    }

    private static OrderResponse canceledOrder() {
        OrderResponse order = baseOrder("2026-10-14T20:00:00Z", "buy", "limit");
        order.setStatus("canceled");
        order.setAmountQuote(new BigDecimal("100"));
        order.setPrice(new BigDecimal("70000"));
        order.setFills(List.of());
        return order;
    }

    private static OrderResponse partialOrder() {
        OrderResponse order = baseOrder("2026-10-14T18:00:00Z", "buy", "limit");
        order.setStatus("partiallyFilled");
        order.setAmount(new BigDecimal("0.002"));
        order.setAmountRemaining(new BigDecimal("0.001"));
        order.setFilledAmount(new BigDecimal("0.001"));
        order.setFilledAmountQuote(new BigDecimal("75.00"));
        order.setFeePaid(new BigDecimal("0.15"));
        order.setFeeCurrency("EUR");
        order.setFills(List.of(fill("2026-10-14T18:00:00Z", "0.001", "75000", "0.15")));
        return order;
    }

    private static OrderResponse baseFeeBuyOrder() {
        OrderResponse order = baseOrder("2026-10-14T12:00:00Z", "buy", "market");
        order.setStatus("filled");
        order.setAmount(new BigDecimal("0.001"));
        order.setFilledAmount(new BigDecimal("0.001"));
        order.setFilledAmountQuote(new BigDecimal("75.00"));
        order.setFeePaid(new BigDecimal("0.000002"));
        order.setFeeCurrency("BTC");
        Fills fill = fill("2026-10-14T12:00:00Z", "0.001", "75000", "0.000002");
        fill.setFeeCurrency("BTC");
        fill.setTaker(false);
        order.setFills(List.of(fill));
        return order;
    }

    private static OrderResponse depositedSellOrder() {
        OrderResponse order = baseOrder("2026-10-14T09:00:00Z", "sell", "market");
        order.setStatus("filled");
        order.setAmount(new BigDecimal("0.001"));
        order.setFilledAmount(new BigDecimal("0.001"));
        order.setFilledAmountQuote(new BigDecimal("75.00"));
        order.setFeePaid(new BigDecimal("0.15"));
        order.setFeeCurrency("EUR");
        order.setFills(List.of(fill("2026-10-14T09:00:00Z", "0.001", "75000", "0.15")));
        return order;
    }

    private static OrderResponse betweenTheFills() {
        OrderResponse order = baseOrder("2026-10-11T09:00:00Z", "buy", "market");
        order.setStatus("filled");
        order.setAmount(new BigDecimal("0.001"));
        order.setFilledAmount(new BigDecimal("0.001"));
        order.setFilledAmountQuote(new BigDecimal("74.00"));
        order.setFeePaid(new BigDecimal("0.15"));
        order.setFeeCurrency("EUR");
        return order;
    }

    private static OrderResponse afterTheFills() {
        OrderResponse order = baseOrder("2026-10-12T09:00:00Z", "buy", "market");
        order.setStatus("filled");
        order.setAmount(new BigDecimal("0.0005"));
        order.setFilledAmount(new BigDecimal("0.0005"));
        order.setFilledAmountQuote(new BigDecimal("37.50"));
        order.setFeePaid(new BigDecimal("0.08"));
        order.setFeeCurrency("EUR");
        return order;
    }

    private static OrderResponse baseOrder(String when, String side, String type) {
        OrderResponse order = new OrderResponse();
        order.setOrderId(java.util.UUID.randomUUID());
        order.setMarket("BTC-EUR");
        order.setCreated(Instant.parse(when).toEpochMilli());
        order.setSide(side);
        order.setOrderType(type);
        return order;
    }

    private static Fills fill(String when, String amount, String price, String fee) {
        Fills fill = new Fills();
        fill.setId(java.util.UUID.randomUUID());
        fill.setTimestamp(Instant.parse(when));
        fill.setAmount(new BigDecimal(amount));
        fill.setPrice(new BigDecimal(price));
        fill.setTaker(true);
        fill.setFee(new BigDecimal(fee));
        fill.setFeeCurrency("EUR");
        fill.setSettled(true);
        return fill;
    }

    private static GetAccountBalanceResponse[] balances() {
        return balances("0.0015");
    }

    private static GetAccountBalanceResponse[] balances(String available) {
        GetAccountBalanceResponse balance = new GetAccountBalanceResponse();
        balance.setSymbol("BTC");
        balance.setAvailable(new BigDecimal(available));
        balance.setInOrder(BigDecimal.ZERO);
        return new GetAccountBalanceResponse[]{balance};
    }

    private static GetAccountBalanceResponse[] withAnotherSymbol() {
        GetAccountBalanceResponse eth = new GetAccountBalanceResponse();
        eth.setSymbol("ETH");
        eth.setAvailable(new BigDecimal("1"));
        eth.setInOrder(BigDecimal.ZERO);
        GetAccountBalanceResponse btc = balances()[0];
        return new GetAccountBalanceResponse[]{eth, btc};
    }

    private static GetBookResponse book() {
        GetBookResponse book = new GetBookResponse();
        book.setMarket("BTC-EUR");
        book.setBid(new BigDecimal("75500"));
        book.setAsk(new BigDecimal("75600"));
        return book;
    }
}
