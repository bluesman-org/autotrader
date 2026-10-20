package nl.jimkaplan.autotrader.strategy.service;

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
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ledger reads the real account, so everything here is a stubbed Bitvavo: a buy before the
 * period, a buy and a sell inside it and an open order, against a fixed clock and zone. The numbers
 * check the rules that matter — a sell is measured against the average cost of the inventory that
 * was bought earlier and a buy shows no result of its own, while the summary's unrealised figure is
 * the open position at the current price — and the balance walks back from today's balance.
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Mock
    private BitvavoApiClient client;
    @Mock
    private BitvavoCredentialsService credentials;
    @Mock
    private LiveStrategyService strategies;
    @Mock
    private MarketService markets;

    private LedgerService service;

    @BeforeEach
    void setUp() {
        service = new LedgerService(client, credentials, strategies, markets,
                new DisplayZone(ZoneId.of("UTC")), Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    @Test
    void ordersShowFillsFeesBalanceAndTheResultAtAverageCost() {
        accountReadable();

        LedgerView view = service.view(new LedgerRequest());

        assertEquals(3, view.rows().size());
        LedgerView.OrderRowView open = view.rows().get(0);
        assertEquals("1", String.valueOf(open.number()));
        assertEquals("15 Oct 2026 08:00", open.dateText());
        assertEquals("BUY", open.side());
        assertEquals("side-buy", open.sideClass());
        assertEquals("limit", open.typeText());
        assertEquals("0,00200000", open.amountText());
        assertEquals("—", open.avgPriceText());
        assertEquals("limit €73.000,00", open.triggerText());
        assertEquals("open", open.statusLabel());
        assertEquals("0,0015", open.balanceAfterText());
        assertEquals("—", open.feeText());
        assertEquals("0", String.valueOf(open.fillsCount()));
        assertEquals("—", open.pnlText());

        LedgerView.OrderRowView sell = view.rows().get(1);
        assertEquals("SELL", sell.side());
        assertEquals("side-sell", sell.sideClass());
        // Sold 0,0005 of an inventory that cost €74.650,00 on average (two buys of €74,15 and
        // €75,15) at €76.000,00, minus the €0,08 fee.
        assertEquals("+€0,60", sell.pnlText());
        assertEquals("pos", sell.pnlClass());
        assertEquals("+€37,92", sell.netCashText());
        assertEquals("€0,08", sell.feeText());
        assertEquals("0,0015", sell.balanceAfterText());
        assertEquals(1, sell.fills().size());
        assertEquals("14 Oct 2026 09:00:00", sell.fills().get(0).timeText());
        assertEquals("0,00050000", sell.fills().get(0).amountText());
        assertEquals("€76.000,00", sell.fills().get(0).priceText());
        assertEquals("€0,0800", sell.fills().get(0).feeText());
        assertEquals("taker", sell.fills().get(0).makerTakerText());
        assertEquals("+€37,92", sell.fills().get(0).netCashText());

        LedgerView.OrderRowView buy = view.rows().get(2);
        // A buy shows no result of its own: the sell that closes it carries it.
        assertEquals("—", buy.pnlText());
        assertEquals("−€75,15", buy.netCashText());
        assertEquals("0,002", buy.balanceAfterText());
        assertEquals("filled", buy.statusLabel());
        assertEquals("ok", buy.statusClass());

        assertEquals("3", String.valueOf(view.totals().orders()));
        assertEquals("€0,23", view.totals().feesText());
        assertEquals("+€0,60", view.totals().realisedText());
        // The open position: 0,0015 BTC at the bid of €75.500,00 against an average cost of
        // €74.650,00.
        assertEquals("+€1,28", view.totals().unrealisedText());
        assertEquals("0,0015 BTC", view.totals().balanceText());
        assertEquals("Last 7 days", view.totals().periodText());
        assertEquals("BTC-EUR", view.price().market());
        assertEquals("€75.600,00", view.price().buyText());
        assertEquals("€75.500,00", view.price().sellText());
    }

    @Test
    void filtersAndPagingKeepTheLedgerState() {
        accountReadable();

        LedgerRequest sellOnly = new LedgerRequest();
        sellOnly.setSide("sell");
        assertEquals(1, service.view(sellOnly).rows().size());

        LedgerRequest openOnly = new LedgerRequest();
        openOnly.setStatus(List.of("open"));
        assertEquals(1, service.view(openOnly).rows().size());

        LedgerRequest waitingOnly = new LedgerRequest();
        waitingOnly.setStatus(List.of("awaitingTrigger"));
        assertEquals(0, service.view(waitingOnly).rows().size());

        LedgerRequest limitOnly = new LedgerRequest();
        limitOnly.setType(List.of("limit"));
        assertEquals(1, service.view(limitOnly).rows().size());

        // Multi-value: every chosen type (or status) counts, and a value the filter cannot use is
        // ignored rather than hiding everything.
        LedgerRequest twoTypes = new LedgerRequest();
        twoTypes.setType(List.of("limit", "market"));
        assertEquals(3, service.view(twoTypes).rows().size());
        LedgerRequest junk = new LedgerRequest();
        junk.setType(List.of("nonsense"));
        assertEquals(3, service.view(junk).rows().size());
        // The pager and rows-per-page links repeat the fields, so the selection survives a page.
        assertTrue(service.view(twoTypes).pager().sizes().get(0).query()
                .contains("type=limit&type=market"));

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
        assertEquals("14 Oct 2026 09:00", fill.timeText());
        assertEquals("sell", fill.typeText());
        assertEquals("side-sell", fill.typeClass());
        assertEquals("0,0005 BTC", fill.sentText());
        assertEquals("€38,00 EUR", fill.receivedText());
        assertEquals("€0,08 EUR", fill.feeText());
        LedgerView.RawRowView deposit = view.rawRows().get(3);
        assertEquals("deposit", deposit.typeText());
        assertEquals("", deposit.typeClass());
        assertEquals("1 Oct 2026 00:00", deposit.timeText());
    }

    @Test
    void withoutAnAccountThePageExplainsItself() {
        when(strategies.all()).thenReturn(List.of());
        when(credentials.get()).thenReturn(Optional.empty());
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        assertNotNull(view.message());
        assertTrue(view.message().contains("No Bitvavo API key is configured"));
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
    void feesFallBackToTheOrderAndFollowTheMarketWhenACurrencyIsMissing() {
        accountReadableWithoutOrders();
        OrderResponse missingCurrency = baseOrder("2026-10-14T10:00:00Z", "buy", "market");
        missingCurrency.setStatus("filled");
        missingCurrency.setAmount(new BigDecimal("0.001"));
        missingCurrency.setFilledAmount(new BigDecimal("0.001"));
        missingCurrency.setFilledAmountQuote(new BigDecimal("75.00"));
        Fills missing = fill("2026-10-14T10:00:00Z", "0.001", "75000", "0.10");
        missing.setFeeCurrency(null);
        missingCurrency.setFills(List.of(missing));

        OrderResponse orderLevelFee = baseOrder("2026-10-14T11:00:00Z", "buy", "market");
        orderLevelFee.setStatus("filled");
        orderLevelFee.setAmount(new BigDecimal("0.001"));
        orderLevelFee.setFilledAmount(new BigDecimal("0.001"));
        orderLevelFee.setFilledAmountQuote(new BigDecimal("75.00"));
        orderLevelFee.setFeePaid(new BigDecimal("0.20"));
        orderLevelFee.setFeeCurrency("EUR");
        Fills withoutFee = fill("2026-10-14T11:00:00Z", "0.001", "75000", "0");
        withoutFee.setFee(null);
        orderLevelFee.setFills(List.of(withoutFee));

        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{orderLevelFee, missingCurrency});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> new GetAccountHistoryResponse(List.of(), 1, 1, 200));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        // A missing currency counts as the market's quote, so the row, its fill, the cash and the
        // period total all carry the same figure.
        LedgerView.OrderRowView missingRow = view.rows().get(1);
        assertEquals("€0,10", missingRow.feeText());
        assertEquals("€0,1000", missingRow.fills().get(0).feeText());
        assertEquals("−€75,10", missingRow.netCashText());
        assertEquals("−€75,10", missingRow.fills().get(0).netCashText());
        assertEquals("—", missingRow.pnlText());
        // A fill that reports no fee of its own takes its share of the order's feePaid, so the row,
        // its fill lines, the cash and the P&L all tell one story.
        LedgerView.OrderRowView fallbackRow = view.rows().get(0);
        assertEquals("€0,20", fallbackRow.feeText());
        assertEquals("−€75,20", fallbackRow.netCashText());
        assertEquals("€0,2000", fallbackRow.fills().get(0).feeText());
        assertEquals("−€75,20", fallbackRow.fills().get(0).netCashText());
        assertEquals("—", fallbackRow.pnlText());
        assertEquals("€0,30", view.totals().feesText());
    }

    @Test
    void fillsWithoutAnyFeeShowNoFeeAtAll() {
        accountReadableWithoutOrders();
        OrderResponse noFee = baseOrder("2026-10-14T10:00:00Z", "buy", "market");
        noFee.setStatus("filled");
        noFee.setAmount(new BigDecimal("0.001"));
        noFee.setFilledAmount(new BigDecimal("0.001"));
        noFee.setFilledAmountQuote(new BigDecimal("75.00"));
        Fills withoutFee = fill("2026-10-14T10:00:00Z", "0.001", "75000", "0");
        withoutFee.setFee(null);
        noFee.setFills(List.of(withoutFee));
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{noFee});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> new GetAccountHistoryResponse(List.of(), 1, 1, 200));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        LedgerView.OrderRowView row = view.rows().get(0);
        assertEquals("—", row.feeText());
        assertEquals("—", row.fills().get(0).feeText());
        assertEquals("−€75,00", row.netCashText());
        assertEquals("−€75,00", row.fills().get(0).netCashText());
        assertEquals("€0,00", view.totals().feesText());
    }

    @Test
    void theHistoryReadsUseTheApisMaximumPageSize() {
        accountReadable();

        service.view(new LedgerRequest());
        LedgerRequest raw = new LedgerRequest();
        raw.setView("raw");
        service.view(raw);

        ArgumentCaptor<String> endpoints = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).get(endpoints.capture(), eq(GetAccountHistoryResponse.class),
                anyString(), anyString());
        // The API rejects a larger page with errorCode 417 ("between 1 and 100"); a plain
        // "maxItems=100" match would also accept 1000, so the value itself is compared.
        assertTrue(endpoints.getAllValues().stream().allMatch(url -> historyPageSize(url) == 100),
                "every account-history read asks for the API's maximum: " + endpoints.getAllValues());
        assertTrue(endpoints.getAllValues().stream().anyMatch(url -> url.contains("type=buy")));
        assertTrue(endpoints.getAllValues().stream().anyMatch(url -> url.contains("type=sell")));
        // The raw view's read carries no type filter.
        assertTrue(endpoints.getAllValues().stream()
                .anyMatch(url -> !url.contains("type=") && historyPageSize(url) == 100));
    }

    private static int historyPageSize(String url) {
        return Integer.parseInt(UriComponentsBuilder.fromUriString(url).build()
                .getQueryParams().getFirst("maxItems"));
    }

    @Test
    void theCustomRangeDefaultsItsOpenEndsAndShowsTheWholeDayTimes() {
        accountReadable();

        // Only a start date: the end runs through today, the times read 00:00 and 23:59.
        LedgerRequest fromOnly = new LedgerRequest();
        fromOnly.setFromDate("2026-10-10");
        LedgerView view = service.view(fromOnly);

        assertEquals("00:00", view.filters().fromTime());
        assertEquals("23:59", view.filters().toTime());
        ArgumentCaptor<String> orders = ArgumentCaptor.forClass(String.class);
        verify(client).get(orders.capture(), eq(OrderResponse[].class), anyString(), anyString());
        assertEquals(String.valueOf(Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()),
                queryParam(orders.getValue(), "start"));
        assertEquals(String.valueOf(Instant.parse("2026-10-15T23:59:59.999Z").toEpochMilli()),
                queryParam(orders.getValue(), "end"));
    }

    @Test
    void aChosenPresetWinsOverLingeringCustomDates() {
        accountReadable();

        // Picking a preset while the form still carries a start date shows and applies the preset.
        LedgerRequest request = new LedgerRequest();
        request.setPeriod("ytd");
        request.setFromDate("2026-09-22");
        LedgerView view = service.view(request);

        assertEquals("Year to date", view.totals().periodText());
        assertEquals(null, view.filters().fromDate());
        assertEquals("ytd", view.filters().period());
        ArgumentCaptor<String> orders = ArgumentCaptor.forClass(String.class);
        verify(client).get(orders.capture(), eq(OrderResponse[].class), anyString(), anyString());
        assertEquals(String.valueOf(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()),
                queryParam(orders.getValue(), "start"));
    }

    @Test
    void anEmptyStartDateMeansEverythingBeforeTheEnd() {
        accountReadable();

        LedgerRequest toOnly = new LedgerRequest();
        toOnly.setToDate("2026-10-14");
        LedgerView view = service.view(toOnly);

        ArgumentCaptor<String> orders = ArgumentCaptor.forClass(String.class);
        verify(client).get(orders.capture(), eq(OrderResponse[].class), anyString(), anyString());
        assertEquals("0", queryParam(orders.getValue(), "start"));
        assertEquals(String.valueOf(Instant.parse("2026-10-14T23:59:59.999Z").toEpochMilli()),
                queryParam(orders.getValue(), "end"));
        assertTrue(view.totals().periodText().startsWith("the start →"));
    }

    @Test
    void theEndOfTodayFollowsTheDisplayZone() {
        // 00:30 on the 16th in Amsterdam, still the 15th in UTC: the range ends at now, and "today"
        // is the display zone's day — the clock's own zone must not decide it.
        service = new LedgerService(client, credentials, strategies, markets,
                new DisplayZone(ZoneId.of("Europe/Amsterdam")),
                Clock.fixed(Instant.parse("2026-10-15T22:30:00Z"), ZoneId.of("UTC")));
        accountReadable();

        LedgerView view = service.view(new LedgerRequest());

        assertEquals("+€1,28", view.totals().unrealisedText());
        assertEquals("", view.totals().unrealisedHint());
    }

    @Test
    void theUnrealisedFigureNeedsARangeThatEndsToday() {
        accountReadable();

        // A range that ends yesterday cannot value a past holding at today's price, so the figure
        // stays a dash and the page explains how to see it.
        LedgerRequest past = new LedgerRequest();
        past.setToDate("2026-10-14");
        LedgerView pastView = service.view(past);
        assertEquals("—", pastView.totals().unrealisedText());
        assertTrue(pastView.totals().unrealisedHint().contains("end the range with today"));

        // A range that ends today — even at an hour already past — shows the open position.
        LedgerRequest today = new LedgerRequest();
        today.setToDate("2026-10-15");
        today.setToTime("09:00");
        LedgerView todayView = service.view(today);
        assertEquals("+€1,28", todayView.totals().unrealisedText());
        assertEquals("", todayView.totals().unrealisedHint());
    }

    @Test
    void waitingOrdersShowTheirTriggerAndTheirLimit() {
        accountReadableWithoutOrders();
        OrderResponse waiting = baseOrder("2026-10-14T20:00:00Z", "sell", "takeProfitLimit");
        waiting.setStatus("awaitingTrigger");
        waiting.setTriggerPrice(new BigDecimal("73162"));
        waiting.setPrice(new BigDecimal("75364"));
        OrderResponse stop = baseOrder("2026-10-14T19:00:00Z", "sell", "stopLoss");
        stop.setStatus("awaitingTrigger");
        stop.setTriggerPrice(new BigDecimal("73000"));
        OrderResponse plainTakeProfit = baseOrder("2026-10-14T19:30:00Z", "sell", "takeProfit");
        plainTakeProfit.setStatus("awaitingTrigger");
        plainTakeProfit.setTriggerPrice(new BigDecimal("73000"));
        // The API documents `price` for a plain takeProfit too, so that branch labels it "price".
        plainTakeProfit.setPrice(new BigDecimal("73100"));
        OrderResponse filledBuy = baseOrder("2026-10-14T18:00:00Z", "buy", "takeProfitLimit");
        filledBuy.setStatus("filled");
        filledBuy.setTriggerPrice(new BigDecimal("74000"));
        filledBuy.setPrice(new BigDecimal("74100"));
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{waiting, plainTakeProfit, stop, filledBuy});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> new GetAccountHistoryResponse(List.of(), 1, 1, 200));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        LedgerView.OrderRowView takeProfit = view.rows().get(0);
        assertEquals("awaiting trigger", takeProfit.statusLabel());
        assertEquals("trigger €73.162,00 · limit €75.364,00", takeProfit.triggerText());
        LedgerView.OrderRowView plainTakeProfitRow = view.rows().get(1);
        assertEquals("trigger €73.000,00 · price €73.100,00", plainTakeProfitRow.triggerText());
        LedgerView.OrderRowView stopLoss = view.rows().get(2);
        assertEquals("trigger €73.000,00", stopLoss.triggerText());
        // The readout is for the orders still open at the exchange; a traded one shows no trigger
        // text (its fills carry the average price instead).
        LedgerView.OrderRowView traded = view.rows().get(3);
        assertEquals("", traded.triggerText());

        // The filter reaches a waiting order by name and through Open.
        LedgerRequest byName = new LedgerRequest();
        byName.setStatus(List.of("awaitingTrigger"));
        assertEquals(3, service.view(byName).rows().size());
        LedgerRequest open = new LedgerRequest();
        open.setStatus(List.of("open"));
        assertEquals(3, service.view(open).rows().size());
    }

    private static String queryParam(String url, String name) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
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
        // Without a price there is no unrealised figure, and the page says why.
        assertEquals("—", view.totals().unrealisedText());
        assertTrue(view.totals().unrealisedHint().contains("current price could not be read"));
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
        assertEquals("€100,00 EUR", canceled.amountText());
        assertEquals("—", canceled.feeText());
        assertEquals("—", canceled.netCashText());
        assertEquals("—", canceled.pnlText());
        assertEquals("0,0015", canceled.balanceAfterText());

        LedgerView.OrderRowView partial = view.rows().get(1);
        assertEquals("partially filled", partial.statusLabel());
        assertEquals("warn", partial.statusClass());
        assertEquals("Filled 0,00100000 of 0,00200000 BTC · 0,00100000 remaining", partial.filledText());
        assertEquals("—", partial.pnlText());

        LedgerView.OrderRowView buyWithBaseFee = view.rows().get(2);
        assertEquals(1, buyWithBaseFee.fills().size());
        // The fee was charged in bitcoin, so it reads as bitcoin — the euro value only enters the
        // P&L, never the display.
        assertEquals("0,000002 BTC", buyWithBaseFee.fills().get(0).feeText());
        assertEquals("maker", buyWithBaseFee.fills().get(0).makerTakerText());
        // A fee taken in the coin does not move the cash, so the net cash is the euro part alone.
        assertEquals("−€75,00", buyWithBaseFee.netCashText());
        assertEquals("−€75,00", buyWithBaseFee.fills().get(0).netCashText());
        assertEquals("0,000002 BTC", buyWithBaseFee.feeText());
        // The period's fees keep each currency apart: €0,15 from the partial fill, the bitcoin fee
        // as bitcoin.
        assertEquals("€0,15 + 0,000002 BTC", view.totals().feesText());
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

        assertEquals("0,000002 BTC", view.rows().get(0).feeText());
        assertEquals("0,000002 BTC", view.totals().feesText());
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
        assertEquals("€75.600,00", view.price().buyText());
    }

    @Test
    void aNonEurMarketFilterFallsBackToTheEngineMarket() {
        accountReadableWithoutOrders();
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> new GetAccountHistoryResponse(List.of(), 1, 1, 200));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerRequest request = new LedgerRequest();
        request.setMarket("BTCUSDT");
        LedgerView view = service.view(request);

        assertEquals("BTC-EUR", view.filters().market());
    }

    @Test
    void theMarketOptionsComeFromTheCatalogue() {
        when(credentials.get()).thenReturn(Optional.of(stored()));
        when(client.get(anyString(), eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(balances());
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> new GetAccountHistoryResponse(List.of(), 1, 1, 200));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());
        when(markets.selectable()).thenReturn(List.of("BTC-EUR", "ETH-EUR"));

        LedgerRequest request = new LedgerRequest();
        request.setMarket("ETH-EUR");
        LedgerView view = service.view(request);

        assertEquals(List.of("all", "BTC-EUR", "ETH-EUR"),
                view.filters().marketOptions().stream().map(option -> option.value()).toList());
    }

    @Test
    void theAllPairsViewMergesTheCatalogueAndSumsTheResult() {
        when(credentials.get()).thenReturn(Optional.of(stored()));
        when(strategies.all()).thenReturn(List.of());
        when(markets.selectable()).thenReturn(List.of("BTC-EUR", "ETH-EUR"));
        List<Transaction> buys = List.of(
                trade("buy", "BTC", "2026-10-01T10:00:00Z", "1", "100", "0"),
                trade("buy", "ETH", "2026-10-01T11:00:00Z", "2", "100", "0"));
        List<Transaction> sells = List.of(
                trade("sell", "BTC", "2026-10-14T10:00:00Z", "1", "110", "0"),
                trade("sell", "ETH", "2026-10-14T11:00:00Z", "2", "90", "0"));
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String endpoint = invocation.getArgument(0);
                    return new GetAccountHistoryResponse(
                            endpoint.contains("type=buy") ? buys : sells, 1, 1, 200);
                });
        OrderResponse btcSell = sellOrder("BTC-EUR", "2026-10-14T10:00:00Z", "1", "110");
        OrderResponse ethSell = sellOrder("ETH-EUR", "2026-10-14T11:00:00Z", "2", "90");
        when(client.get(argThat(url -> url != null && url.contains("market=BTC-EUR")),
                eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{btcSell});
        when(client.get(argThat(url -> url != null && url.contains("market=ETH-EUR")),
                eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{ethSell});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(new GetAccountBalanceResponse[]{
                        balanceOf("BTC", "0"), balanceOf("ETH", "0"), balanceOf("EUR", "1000")});
        when(client.getPublic(eq("/ticker/book"), eq(GetBookResponse[].class)))
                .thenReturn(new GetBookResponse[]{bookFor("BTC-EUR", "120"), bookFor("ETH-EUR", "95")});

        LedgerRequest request = new LedgerRequest();
        request.setMarket("all");
        LedgerView view = service.view(request);

        assertNull(view.message());
        assertNull(view.price());
        assertEquals("all", view.filters().market());
        assertEquals("Amount", view.filters().amountHeader());
        assertEquals("All pairs", view.filters().marketOptions().get(0).label());
        assertEquals(2, view.rows().size());
        assertEquals("ETH-EUR", view.rows().get(0).marketText());
        assertEquals("−€20,00", view.rows().get(0).pnlText());
        assertEquals("+€10,00", view.rows().get(1).pnlText());
        assertEquals("−€10,00", view.totals().realisedText());
        assertEquals("€0,00", view.totals().feesText());
        assertEquals("€1.000,00", view.totals().balanceText());
        assertEquals("—", view.totals().unrealisedText());
        assertTrue(view.totals().unrealisedHint().contains("Nothing is held"));
    }

    @Test
    void theAllPairsViewKeepsGoingWhenAMarketCannotBeRead() {
        when(credentials.get()).thenReturn(Optional.of(stored()));
        when(strategies.all()).thenReturn(List.of());
        when(markets.selectable()).thenReturn(List.of("BTC-EUR", "ETH-EUR"));
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenReturn(new GetAccountHistoryResponse(List.of(), 1, 1, 200));
        when(client.get(argThat(url -> url != null && url.contains("market=BTC-EUR")),
                eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{sellOrder("BTC-EUR", "2026-10-14T10:00:00Z", "1", "110")});
        when(client.get(argThat(url -> url != null && url.contains("market=ETH-EUR")),
                eq(OrderResponse[].class), anyString(), anyString()))
                .thenThrow(new RestClientException("400"));
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(new GetAccountBalanceResponse[]{balanceOf("BTC", "0"), balanceOf("EUR", "1000")});
        when(client.getPublic(eq("/ticker/book"), eq(GetBookResponse[].class)))
                .thenReturn(new GetBookResponse[]{bookFor("BTC-EUR", "120")});

        LedgerRequest request = new LedgerRequest();
        request.setMarket("all");
        LedgerView view = service.view(request);

        assertEquals(1, view.rows().size());
        assertEquals("BTC-EUR", view.rows().get(0).marketText());
        assertTrue(view.totals().note().contains("ETH-EUR"));
    }

    @Test
    void theAllPairsViewNeedsAPriceForEveryHeldPair() {
        when(credentials.get()).thenReturn(Optional.of(stored()));
        when(strategies.all()).thenReturn(List.of());
        when(markets.selectable()).thenReturn(List.of("BTC-EUR"));
        when(client.get(anyString(), eq(GetAccountHistoryResponse.class), anyString(), anyString()))
                .thenAnswer(invocation -> new GetAccountHistoryResponse(
                        invocation.getArgument(0).toString().contains("type=buy")
                                ? List.of(trade("buy", "BTC", "2026-10-01T10:00:00Z", "1", "100", "0"))
                                : List.of(),
                        1, 1, 200));
        when(client.get(argThat(url -> url != null && url.contains("market=BTC-EUR")),
                eq(OrderResponse[].class), anyString(), anyString()))
                .thenReturn(new OrderResponse[]{});
        when(client.get(anyString(), eq(GetAccountBalanceResponse[].class), anyString(), anyString()))
                .thenReturn(new GetAccountBalanceResponse[]{balanceOf("BTC", "1"), balanceOf("EUR", "1000")});
        when(client.getPublic(eq("/ticker/book"), eq(GetBookResponse[].class)))
                .thenReturn(new GetBookResponse[]{});

        LedgerRequest request = new LedgerRequest();
        request.setMarket("all");
        LedgerView view = service.view(request);

        assertEquals("—", view.totals().unrealisedText());
        assertTrue(view.totals().unrealisedHint().contains("could not be read"));
    }

    @Test
    void theDefaultMarketFallsBackToTheCatalogueWhenNoStrategyRuns() {
        when(markets.selectable()).thenReturn(List.of("ETH-EUR", "BTC-EUR"));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        assertEquals("ETH-EUR", view.filters().market());
        assertEquals("ETH-EUR", view.price().market());
    }

    @Test
    void theDefaultMarketIgnoresANonEurStrategy() {
        LiveStrategy nonEur = org.mockito.Mockito.mock(LiveStrategy.class);
        when(nonEur.getMarket()).thenReturn("BTCUSDT");
        when(strategies.all()).thenReturn(List.of(nonEur));
        when(markets.selectable()).thenReturn(List.of("ETH-EUR"));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        assertEquals("ETH-EUR", view.filters().market());
    }

    @Test
    void theDefaultMarketTakesTheNewestEurStrategy() {
        // Real instances, not mocks: strict stubbing would flag a getter the fixed chain never
        // reads, and a null createdTime would sort before the EUR rows instead of after them.
        LiveStrategy newestNonEur = LiveStrategy.builder()
                .market("BTCUSDT")
                .createdTime(Instant.parse("2026-09-01T00:00:00Z"))
                .build();
        LiveStrategy newerEur = LiveStrategy.builder()
                .market("SOL-EUR")
                .createdTime(Instant.parse("2026-06-01T00:00:00Z"))
                .build();
        LiveStrategy olderEur = LiveStrategy.builder()
                .market("ETH-EUR")
                .createdTime(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(strategies.all()).thenReturn(List.of(olderEur, newestNonEur, newerEur));
        when(markets.selectable()).thenReturn(List.of("ETH-EUR"));
        when(client.getPublic(anyString(), eq(GetBookResponse.class))).thenReturn(book());

        LedgerView view = service.view(new LedgerRequest());

        // With the filter after max() this would fall through to the catalogue's ETH-EUR.
        assertEquals("SOL-EUR", view.filters().market());
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
                        // Quoted in bitcoin, not euro: the cost walk cannot price it, but the
                        // bitcoin it spent still has to leave the bitcoin balance.
                        paidWithBase.setPriceCurrency("BTC");
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

        // The account holds 0,0005 BTC today: after the BTC buy 0,001, after paying with BTC 0,0005.
        assertEquals("0,0005", view.rows().get(0).balanceAfterText());
        assertEquals("0,001", view.rows().get(1).balanceAfterText());
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
        assertEquals("−€0,15", view.rows().get(0).pnlText());
        assertEquals("0,0015 BTC", view.totals().balanceText());
        // Nothing held in the walk, so there is no position to value.
        assertEquals("—", view.totals().unrealisedText());
        assertTrue(view.totals().unrealisedHint().contains("Nothing is held"));
    }

    @Test
    void theMarketFollowsTheRequestOtherwiseTheEngine() {
        when(credentials.get()).thenReturn(Optional.of(stored()));
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
        assertEquals(2, view.filters().marketOptions().size());
        assertEquals("all", view.filters().marketOptions().get(0).value());
        assertEquals("All pairs", view.filters().marketOptions().get(0).label());
        assertEquals("ETH-EUR", view.filters().marketOptions().get(1).value());
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
        when(strategy.getMarket()).thenReturn("BTC-EUR");
        when(strategies.all()).thenReturn(List.of(strategy));
        when(credentials.get()).thenReturn(Optional.of(stored()));
    }

    private static BitvavoCredentials stored() {
        return BitvavoCredentials.builder()
                .id(BitvavoCredentials.ID)
                .apiKey("key")
                .apiSecret("secret")
                .build();
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

    private static OrderResponse sellOrder(String market, String when, String amount, String price) {
        OrderResponse order = baseOrder(when, "sell", "market");
        order.setMarket(market);
        order.setStatus("filled");
        order.setAmount(new BigDecimal(amount));
        order.setFilledAmount(new BigDecimal(amount));
        order.setFilledAmountQuote(new BigDecimal(amount).multiply(new BigDecimal(price)));
        order.setFeePaid(BigDecimal.ZERO);
        order.setFills(List.of(fill(when, amount, price, "0")));
        return order;
    }

    private static Transaction trade(String type, String base, String when, String amount,
                                     String price, String fee) {
        Transaction entry = new Transaction();
        entry.setType(type);
        entry.setExecutedAt(Instant.parse(when));
        entry.setPriceCurrency("EUR");
        entry.setPriceAmount(new BigDecimal(price));
        BigDecimal quantity = new BigDecimal(amount);
        BigDecimal cash = quantity.multiply(new BigDecimal(price));
        if ("buy".equals(type)) {
            entry.setSentCurrency("EUR");
            entry.setSentAmount(cash);
            entry.setReceivedCurrency(base);
            entry.setReceivedAmount(quantity);
        } else {
            entry.setSentCurrency(base);
            entry.setSentAmount(quantity);
            entry.setReceivedCurrency("EUR");
            entry.setReceivedAmount(cash);
        }
        entry.setFeesCurrency("EUR");
        entry.setFeesAmount(new BigDecimal(fee));
        return entry;
    }

    private static GetAccountBalanceResponse balanceOf(String symbol, String available) {
        GetAccountBalanceResponse balance = new GetAccountBalanceResponse();
        balance.setSymbol(symbol);
        balance.setAvailable(new BigDecimal(available));
        balance.setInOrder(BigDecimal.ZERO);
        return balance;
    }

    private static GetBookResponse bookFor(String market, String bid) {
        GetBookResponse book = new GetBookResponse();
        book.setMarket(market);
        book.setBid(new BigDecimal(bid));
        book.setAsk(new BigDecimal(bid));
        return book;
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
