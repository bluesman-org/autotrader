package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.Fills;
import nl.jimkaplan.autotrader.bitvavo.model.GetBookResponse;
import nl.jimkaplan.autotrader.bitvavo.model.OrderResponse;
import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.strategy.controller.ExternalCloseView;
import nl.jimkaplan.autotrader.strategy.controller.LiveCloseResult;
import nl.jimkaplan.autotrader.strategy.controller.LiveCloseView;
import nl.jimkaplan.autotrader.strategy.controller.ReconcileView;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveCloseServiceTest {

    private static final String MARKET = "BTC-EUR";
    private static final String STRATEGY_ID = "strategy-1";
    private static final long ENTRY = Instant.parse("2026-10-20T14:00:00Z").toEpochMilli();
    private static final Instant FILLED_AT = Instant.parse("2026-10-21T14:00:00Z");
    private static final String FULL_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SMALL_ID = "22222222-2222-2222-2222-222222222222";
    private static final String ENGINE_ID = "33333333-3333-3333-3333-333333333333";
    private static final String DUPLICATE_ID = "44444444-4444-4444-4444-444444444444";

    @Mock
    private LivePositionRepository livePositionRepository;

    @Mock
    private LiveOrderRepository liveOrderRepository;

    @Mock
    private BitvavoCredentialsService credentialsService;

    @Mock
    private BitvavoApiClient bitvavoApiClient;

    @Mock
    private LiveTradingService liveTradingService;

    private LiveCloseService service;

    @BeforeEach
    void setUp() {
        service = new LiveCloseService(livePositionRepository, liveOrderRepository, credentialsService,
                bitvavoApiClient, liveTradingService, "UTC");
    }

    @Test
    void previewValuesThePositionAtTheBid() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenReturn(book(73_400));

        LiveCloseView view = service.preview(realStrategy(), position());

        assertTrue(view.priced());
        assertFalse(view.dryRun());
        assertEquals("LONG 0,00100000 BTC", view.quantityText());
        assertEquals("20 Oct 2026 16:00 · €73.000,00", view.entryText());
        assertEquals("€0,20", view.entryFeeText());
        assertEquals("€73.400,00", view.bidText());
        assertEquals("€73,40", view.proceedsText());
        assertEquals("€0,15", view.feeText());
        assertEquals("+€0,05", view.profitText());
        assertEquals("pos", view.profitClass());
        assertTrue(view.warning().contains("real market sell"));
    }

    @Test
    void aDryPreviewSaysItIsSimulated() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenReturn(book(73_400));

        LiveCloseView view = service.preview(dryStrategy(), position());

        assertTrue(view.dryRun());
        assertTrue(view.warning().contains("simulated sell"));
    }

    @Test
    void previewWithoutABookIsUnpriced() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenThrow(new RestClientException("connect timed out"));

        LiveCloseView view = service.preview(realStrategy(), position());

        assertFalse(view.priced());
        assertEquals("—", view.bidText());
        assertEquals("—", view.profitText());
        assertEquals("", view.profitClass());
        assertTrue(view.warning().contains("current price is unavailable"));
    }

    @Test
    void closeSendsTheBidToTheSellPathAndReportsTheClose() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenReturn(book(73_400));
        LiveOrder order = LiveOrder.builder().side("SELL").status("FILLED")
                .orderId("order-1").price(73_400.0).build();
        LivePosition position = position();
        when(liveTradingService.closeManually(eq(realStrategy()), eq(position), anyLong(),
                eq("manual close in the app"), eq(73_400.0))).thenAnswer(invocation -> {
            position.setStatus("CLOSED");
            return order;
        });

        LiveCloseResult result = service.close(realStrategy(), position);

        assertTrue(result.closed());
        assertEquals("FILLED", result.status());
        assertEquals("order-1", result.orderId());
        assertTrue(result.message().contains("The position was closed at €73.400,00"));
    }

    @Test
    void closeWhenTheSellIsNotFilledKeepsThePositionOpenAndExplainsWhy() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenReturn(book(73_400));
        LiveOrder order = LiveOrder.builder().side("SELL").status("PENDING").orderId("order-2").build();
        LivePosition position = position();
        when(liveTradingService.closeManually(eq(realStrategy()), eq(position), anyLong(),
                eq("manual close in the app"), eq(73_400.0))).thenReturn(order);

        LiveCloseResult result = service.close(realStrategy(), position);

        assertFalse(result.closed());
        assertEquals("PENDING", result.status());
        assertTrue(result.message().contains("not filled yet"));
    }

    @Test
    void closeExplainsARejection() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenReturn(book(73_400));
        LiveOrder order = LiveOrder.builder().status("REJECTED")
                .errorMessage("No Bitvavo API key is configured").build();
        LivePosition position = position();
        when(liveTradingService.closeManually(eq(realStrategy()), eq(position), anyLong(),
                eq("manual close in the app"), eq(73_400.0))).thenReturn(order);

        LiveCloseResult result = service.close(realStrategy(), position);

        assertFalse(result.closed());
        assertEquals("REJECTED", result.status());
        assertTrue(result.message().contains("No Bitvavo API key is configured"));
        assertTrue(result.message().contains("stays open"));
    }

    @Test
    void closeWithoutABookDoesNotTry() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenThrow(new RestClientException("connect timed out"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.close(realStrategy(), position()));

        assertTrue(error.getMessage().contains("current price is unavailable"));
        verify(liveTradingService, never()).closeManually(any(), any(), anyLong(), any(), anyDouble());
    }

    @Test
    void previewWithoutAnEntryFeeOrABidIsStillSafe() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenReturn(new GetBookResponse(MARKET, null, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE));
        LiveStrategy unnamed = realStrategy();
        unnamed.setName(null);
        LivePosition position = position();
        position.setEntryFee(null);

        LiveCloseView view = service.preview(unnamed, position);

        assertFalse(view.priced());
        assertEquals("BTC-EUR 2h", view.name());
        assertEquals("—", view.entryFeeText());
        assertEquals("—", view.profitText());
    }

    @Test
    void aNegativeResultIsMarkedNeg() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenReturn(book(72_000));

        LiveCloseView view = service.preview(realStrategy(), position());

        assertEquals("neg", view.profitClass());
        assertTrue(view.profitText().startsWith("−"));
    }

    @Test
    void closeWithoutABidDoesNotTry() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenReturn(new GetBookResponse(MARKET, null, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE));

        assertThrows(IllegalArgumentException.class, () -> service.close(realStrategy(), position()));
        verify(liveTradingService, never()).closeManually(any(), any(), anyLong(), any(), anyDouble());
    }

    @Test
    void closeMessagesCoverTheDryNullReferenceAndFailedCases() {
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenReturn(book(73_400));
        LivePosition position = position();
        when(liveTradingService.closeManually(eq(dryStrategy()), eq(position), anyLong(),
                eq("manual close in the app"), eq(73_400.0))).thenAnswer(invocation -> {
            position.setStatus("CLOSED");
            return LiveOrder.builder().status("DRY_RUN").build();
        });

        LiveCloseResult dry = service.close(dryStrategy(), position);
        assertEquals("The position was closed.", dry.message());

        LivePosition failedPosition = position();
        when(liveTradingService.closeManually(eq(realStrategy()), eq(failedPosition), anyLong(),
                eq("manual close in the app"), eq(73_400.0)))
                .thenReturn(LiveOrder.builder().status("FAILED")
                        .errorMessage("canceledMarketProtection").build());
        LiveCloseResult failed = service.close(realStrategy(), failedPosition);
        assertTrue(failed.message().contains("not settled"));
        assertTrue(failed.message().contains("canceledMarketProtection"));

        LivePosition unreported = position();
        when(liveTradingService.closeManually(eq(realStrategy()), eq(unreported), anyLong(),
                eq("manual close in the app"), eq(73_400.0)))
                .thenReturn(LiveOrder.builder().status("FAILED").build());
        LiveCloseResult noReason = service.close(realStrategy(), unreported);
        assertTrue(noReason.message().contains("did not give a reason"));
    }

    @Test
    void reconcileViewListsExternalSellsAndSkipsEngineDuplicatesAndIncompleteOrders() {
        when(credentialsService.get()).thenReturn(Optional.of(credentials()));
        when(bitvavoApiClient.get(eq(historyUrl()), eq(OrderResponse[].class), eq("key"), eq("secret")))
                .thenReturn(new OrderResponse[]{externalSell(FULL_ID, "0.001", "73.40", "0.1468", FILLED_AT),
                        engineSell(ENGINE_ID), externalSell(DUPLICATE_ID, "0.001", "73.40", "0.1468", FILLED_AT),
                        externalSell(SMALL_ID, "0.0005", "36.70", "0.0734", FILLED_AT),
                        new OrderResponse()});
        when(liveOrderRepository.existsByOrderId(FULL_ID)).thenReturn(false);
        when(liveOrderRepository.existsByOrderId(DUPLICATE_ID)).thenReturn(true);
        when(liveOrderRepository.existsByOrderId(SMALL_ID)).thenReturn(false);

        ReconcileView view = service.reconcileView(realStrategy(), position());

        assertEquals(2, view.candidates().size());
        assertNull(view.note());
        ExternalCloseView full = candidate(view, FULL_ID);
        ExternalCloseView small = candidate(view, SMALL_ID);
        assertTrue(full.coversPosition());
        assertEquals("covers the position", full.coverText());
        assertFalse(small.coversPosition());
        assertTrue(small.coverText().contains("covers only 0,00050000"));
    }

    @Test
    void reconcileViewExplainsAnEmptyListAndAFullPage() {
        when(credentialsService.get()).thenReturn(Optional.of(credentials()));
        when(bitvavoApiClient.get(eq(historyUrl()), eq(OrderResponse[].class), eq("key"), eq("secret")))
                .thenReturn(new OrderResponse[0]);

        ReconcileView empty = service.reconcileView(realStrategy(), position());
        assertTrue(empty.note().contains("No filled sell"));

        OrderResponse[] page = new OrderResponse[LiveCloseService.ORDER_HISTORY_LIMIT];
        page[0] = externalSell(FULL_ID, "0.001", "73.40", "0.1468", FILLED_AT);
        when(liveOrderRepository.existsByOrderId(FULL_ID)).thenReturn(false);
        when(bitvavoApiClient.get(eq(historyUrl()), eq(OrderResponse[].class), eq("key"), eq("secret")))
                .thenReturn(page);

        ReconcileView full = service.reconcileView(realStrategy(), position());
        assertTrue(full.note().contains("full page"));

        OrderResponse[] emptyPage = new OrderResponse[LiveCloseService.ORDER_HISTORY_LIMIT];
        when(bitvavoApiClient.get(eq(historyUrl()), eq(OrderResponse[].class), eq("key"), eq("secret")))
                .thenReturn(emptyPage);

        ReconcileView emptyFull = service.reconcileView(realStrategy(), position());
        assertTrue(emptyFull.note().contains("No filled sell"));
        assertTrue(emptyFull.note().contains("full page"));
    }

    @Test
    void aReconcileViewWithoutAnEntryFeeShowsADash() {
        LivePosition position = position();
        position.setEntryFee(null);
        stubHistory(new OrderResponse[0]);

        ReconcileView view = service.reconcileView(realStrategy(), position);

        assertEquals("—", view.entryFeeText());
    }

    @Test
    void reconcileViewSkipsAnOrderWithoutAnIdAndUsesTheLatestFill() {
        OrderResponse noId = externalSell(FULL_ID, "0.001", "73.40", "0.1468", FILLED_AT);
        noId.setOrderId(null);
        OrderResponse multi = externalSell(SMALL_ID, "0.001", "73.40", "0.1468", FILLED_AT);
        multi.setCreated(null);
        Fills earlier = new Fills();
        earlier.setTimestamp(FILLED_AT.minusSeconds(60));
        Fills later = new Fills();
        later.setTimestamp(FILLED_AT);
        multi.setFills(List.of(earlier, later));
        when(liveOrderRepository.existsByOrderId(SMALL_ID)).thenReturn(false);
        stubHistory(new OrderResponse[]{null, noId, multi});

        ReconcileView view = service.reconcileView(realStrategy(), position());

        assertEquals(1, view.candidates().size());
        assertTrue(view.candidates().get(0).timeText().contains("21 Oct 2026 14:00"));
    }

    @Test
    void reconcileRejectsBlankZeroNegativeAndUndatedSells() {
        assertReconcileError("Choose the exchange sell that closed the position.", " ");

        stubHistory(new OrderResponse[]{externalSell(FULL_ID, "0", "0", "0", FILLED_AT)});
        assertReconcileError("sold quantity or proceeds", FULL_ID);

        stubHistory(new OrderResponse[]{
                externalSell(FULL_ID, "0.001", "-73.40", "0.1468", FILLED_AT)});
        assertReconcileError("usable fill price", FULL_ID);

        OrderResponse undated = externalSell(FULL_ID, "0.001", "73.40", "0.1468", FILLED_AT);
        undated.setFills(null);
        undated.setCreated(null);
        stubHistory(new OrderResponse[]{undated});
        assertReconcileError("did not report when", FULL_ID);
    }

    @Test
    void reconcileClosesThePositionAtTheAverageFillAndProratesALargerSell() {
        when(liveOrderRepository.existsByOrderId(FULL_ID)).thenReturn(false);
        when(credentialsService.get()).thenReturn(Optional.of(credentials()));
        when(bitvavoApiClient.get(eq(historyUrl()), eq(OrderResponse[].class), eq("key"), eq("secret")))
                .thenReturn(new OrderResponse[]{
                        null, externalSell(FULL_ID, "0.002", "146.80", "0.2936", FILLED_AT)});
        LivePosition position = position();

        LiveOrder row = service.reconcile(realStrategy(), position, FULL_ID);

        assertTrue(row.isExternal());
        assertEquals("SELL", row.getSide());
        assertEquals("FILLED", row.getStatus());
        assertEquals(FULL_ID, row.getOrderId());
        assertNull(row.getClientOrderId());
        assertEquals(0.001, row.getQuantity(), 1e-12);
        assertEquals(73_400.0, row.getPrice(), 1e-9);
        assertEquals(73.40, row.getAmountQuote(), 1e-9);
        assertEquals(0.1468, row.getFee(), 1e-9);
        assertEquals(FILLED_AT.toEpochMilli(), row.getSignalOpenTime());
        assertEquals("manual close outside the engine (operator-reconciled)", row.getReason());

        assertEquals("CLOSED", position.getStatus());
        assertEquals(73_400.0, position.getExitPrice(), 1e-9);
        assertEquals(FILLED_AT.toEpochMilli(), position.getExitTime());
        assertEquals(73.40 - 73.0 - 0.1468 - 0.2, position.getProfit(), 1e-9);
        verify(liveOrderRepository).save(row);
        verify(livePositionRepository).save(position);
    }

    @Test
    void reconcileRejectsWhatItCannotVerify() {
        assertReconcileError("Choose the exchange sell that closed the position.", null);

        when(liveOrderRepository.existsByOrderId(FULL_ID)).thenReturn(true);
        assertReconcileError("already attached", FULL_ID);

        when(liveOrderRepository.existsByOrderId(FULL_ID)).thenReturn(false);
        stubHistory(new OrderResponse[0]);
        assertReconcileError("not found in the exchange's order history", FULL_ID);

        stubHistory(new OrderResponse[]{engineSell(ENGINE_ID)});
        assertReconcileError("not a filled sell made outside the engine", ENGINE_ID);

        stubHistory(new OrderResponse[]{externalSell(SMALL_ID, "0.0005", "36.70", "0.0734", FILLED_AT)});
        assertReconcileError("covers only", SMALL_ID);

        stubHistory(new OrderResponse[]{externalSell(FULL_ID, "0.001", "73.40", null, FILLED_AT)});
        assertReconcileError("did not report the sell's fee", FULL_ID);

        stubHistory(new OrderResponse[]{externalSell(FULL_ID, "0.001", null, "0.1468", FILLED_AT)});
        assertReconcileError("sold quantity or proceeds", FULL_ID);
    }

    @Test
    void historyNeedsAKeyAndAFullAnswer() {
        when(credentialsService.get()).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.reconcileView(realStrategy(), position()));

        when(credentialsService.get()).thenReturn(Optional.of(credentials()));
        when(bitvavoApiClient.get(eq(historyUrl()), eq(OrderResponse[].class), eq("key"), eq("secret")))
                .thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.reconcileView(realStrategy(), position()));
    }

    private void assertReconcileError(String expected, String orderId) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.reconcile(realStrategy(), position(), orderId));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    private void stubHistory(OrderResponse[] orders) {
        when(credentialsService.get()).thenReturn(Optional.of(credentials()));
        when(bitvavoApiClient.get(eq(historyUrl()), eq(OrderResponse[].class), eq("key"), eq("secret")))
                .thenReturn(orders);
    }

    private static ExternalCloseView candidate(ReconcileView view, String orderId) {
        return view.candidates().stream().filter(candidate -> candidate.orderId().equals(orderId))
                .findFirst().orElseThrow();
    }

    private static String historyUrl() {
        return "/orders?market=" + MARKET + "&start=" + ENTRY + "&limit=" + LiveCloseService.ORDER_HISTORY_LIMIT;
    }

    private static GetBookResponse book(double bid) {
        return new GetBookResponse(MARKET, BigDecimal.valueOf(bid), BigDecimal.ONE,
                BigDecimal.valueOf(bid + 20), BigDecimal.ONE);
    }

    private static BitvavoCredentials credentials() {
        return BitvavoCredentials.builder().apiKey("key").apiSecret("secret").operatorId(1L).build();
    }

    private static LiveStrategy realStrategy() {
        return strategy(false);
    }

    private static LiveStrategy dryStrategy() {
        return strategy(true);
    }

    private static LiveStrategy strategy(boolean dryRun) {
        LiveStrategy strategy = LiveStrategy.builder()
                .name("BTC 2h").market(MARKET).interval("2h")
                .entryOperator("ALL").entryConditions(List.of("MACD_BULLISH_CROSSOVER"))
                .exitOperator("ALL").exitConditions(List.of("SAR_BEARISH_CROSSOVER"))
                .amountPerBuy(100).warmUpCandles(60).dryRun(dryRun)
                .build();
        strategy.setId(STRATEGY_ID);
        return strategy;
    }

    private static LivePosition position() {
        return LivePosition.builder()
                .strategyId(STRATEGY_ID).market(MARKET).status("OPEN")
                .quantity(0.001).entryPrice(73_000).entryFee(0.2)
                .entryTime(ENTRY).dryRun(false)
                .build();
    }

    private static OrderResponse externalSell(String orderId, String quantity, String quote,
                                              String fee, Instant filledAt) {
        OrderResponse order = sell(orderId, quantity, quote, fee, filledAt);
        order.setClientOrderId(null);
        return order;
    }

    private static OrderResponse engineSell(String orderId) {
        OrderResponse order = sell(orderId, "0.001", "73.40", "0.1468", FILLED_AT);
        order.setClientOrderId(UUID.randomUUID());
        return order;
    }

    private static OrderResponse sell(String orderId, String quantity, String quote, String fee,
                                      Instant filledAt) {
        OrderResponse order = new OrderResponse();
        order.setOrderId(UUID.fromString(orderId));
        order.setMarket(MARKET);
        order.setCreated(filledAt.toEpochMilli());
        order.setStatus("filled");
        order.setSide("sell");
        if (quantity != null) {
            order.setFilledAmount(new BigDecimal(quantity));
        }
        if (quote != null) {
            order.setFilledAmountQuote(new BigDecimal(quote));
        }
        if (fee != null) {
            order.setFeePaid(new BigDecimal(fee));
        }
        Fills fill = new Fills();
        fill.setTimestamp(filledAt);
        fill.setAmount(new BigDecimal(quantity == null ? "0.001" : quantity));
        fill.setPrice(new BigDecimal("73400"));
        fill.setSettled(true);
        order.setFills(List.of(fill));
        return order;
    }
}
