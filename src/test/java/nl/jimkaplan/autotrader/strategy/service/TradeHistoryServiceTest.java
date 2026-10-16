package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryRequest;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeHistoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final String STRATEGY = "6ace5dbf7b4e0870883058cb";
    private static final long HOUR = 3_600_000L;

    @Mock
    private LivePositionRepository positions;

    @Mock
    private LiveOrderRepository orders;

    private TradeHistoryService service;

    @BeforeEach
    void setUp() {
        service = new TradeHistoryService(positions, orders, ZoneId.of("UTC"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void view_defaultsToTheLastSevenDaysNewestFirst() {
        // The repository applies the period; the stub returns only what falls inside the range.
        stubPeriod(List.of(
                position(STRATEGY, NOW.minusSeconds(2 * 3600).toEpochMilli(), 0.12, true),
                position(STRATEGY, NOW.minusSeconds(3 * 3600).toEpochMilli(), -0.11, false)));

        TradeHistoryView view = service.view(new TradeHistoryRequest());

        assertEquals(2, view.rows().size());
        assertEquals(1, view.rows().get(0).number());
        assertTrue(view.rows().get(0).entryText().startsWith("15 Oct 10:00"));
        assertEquals("15 Oct 09:00", view.rows().get(1).entryText());
        assertEquals("DRY RUN", view.rows().get(0).typeLabel());
        assertEquals("REAL", view.rows().get(1).typeLabel());
        assertEquals("+€0,12", view.rows().get(0).resultText());
        assertEquals("pos", view.rows().get(0).resultClass());
        assertEquals("−€0,11", view.rows().get(1).resultText());

        assertEquals(2, view.totals().roundTrips());
        assertEquals("+€0,01", view.totals().realisedText());
        assertEquals("50%", view.totals().winRateText());
        assertEquals("Last 7 days", view.totals().periodText());
        assertEquals(1, view.pager().page());
        assertNull(view.pager().prevQuery());
        assertNull(view.pager().nextQuery());
        assertEquals(1, view.filters().strategyOptions().size());
        assertEquals(STRATEGY, view.filters().strategyOptions().get(0).value());
        assertEquals("6ace5dbf", view.filters().strategyOptions().get(0).label());
        assertEquals("7d", view.filters().period());
        assertFalse(view.headers().isEmpty());
        assertTrue(view.headers().get(0).active());
        assertEquals("▼", view.headers().get(0).arrow());
    }

    @Test
    void view_appliesStrategyTypeAndResultFilters() {
        stubPeriod(List.of(
                position(STRATEGY, NOW.minusSeconds(2 * 3600).toEpochMilli(), 0.12, true),
                position(STRATEGY, NOW.minusSeconds(3 * 3600).toEpochMilli(), -0.11, true),
                position("other-strategy-id", NOW.minusSeconds(4 * 3600).toEpochMilli(), -0.30, false)));

        TradeHistoryRequest dryLosses = new TradeHistoryRequest();
        dryLosses.setStrategy(STRATEGY);
        dryLosses.setType("dry");
        dryLosses.setResult("losses");
        TradeHistoryView view = service.view(dryLosses);

        assertEquals(1, view.rows().size());
        assertEquals("−€0,11", view.rows().get(0).resultText());
        assertEquals(1, view.totals().roundTrips());
        assertEquals(0, view.totals().winRateText().equals("50%") ? 1 : 0);
        assertEquals("0%", view.totals().winRateText());
    }

    @Test
    void view_sortsByResultAndMarksTheHeader() {
        stubPeriod(List.of(
                position(STRATEGY, NOW.minusSeconds(2 * 3600).toEpochMilli(), 0.12, true),
                position(STRATEGY, NOW.minusSeconds(3 * 3600).toEpochMilli(), -0.11, true),
                position(STRATEGY, NOW.minusSeconds(4 * 3600).toEpochMilli(), 0.53, true)));

        TradeHistoryRequest request = new TradeHistoryRequest();
        request.setSort("result");
        request.setDir("asc");
        TradeHistoryView view = service.view(request);

        assertEquals("−€0,11", view.rows().get(0).resultText());
        assertEquals("+€0,12", view.rows().get(1).resultText());
        assertEquals("+€0,53", view.rows().get(2).resultText());
        TradeHistoryView.HeaderView result = view.headers().stream()
                .filter(header -> header.label().equals("Result")).findFirst().orElseThrow();
        assertTrue(result.active());
        assertEquals("▲", result.arrow());
        assertTrue(result.query().contains("sort=result"));
        assertTrue(result.query().contains("dir=desc"));
    }

    @Test
    void view_pagesInMemoryWhileTheTotalsCoverTheWholePeriod() {
        List<LivePosition> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(position(STRATEGY, NOW.minusSeconds((i + 1) * 3600L).toEpochMilli(), 0.01, true));
        }
        stubPeriod(many);

        TradeHistoryRequest request = new TradeHistoryRequest();
        request.setSize(5);
        request.setPage(2);
        TradeHistoryView view = service.view(request);

        assertEquals(5, view.rows().size());
        assertEquals(6, view.rows().get(0).number());
        assertEquals(10, view.rows().get(4).number());
        assertEquals(12, view.pager().total());
        assertEquals(3, view.pager().pages());
        assertEquals(6, view.pager().from());
        assertEquals(10, view.pager().to());
        // page=1 is the default and deliberately omitted from the links
        assertFalse(view.pager().prevQuery().contains("page="));
        assertTrue(view.pager().nextQuery().contains("page=3"));
        assertEquals(12, view.totals().roundTrips());
        assertTrue(view.pager().sizes().stream().anyMatch(size -> size.label().equals("5") && size.selected()));
    }

    @Test
    void view_resolvesCalendarPresetsInTheDisplayZone() {
        when(positions.findByStatusAndEntryTimeRange(
                eq("CLOSED"),
                eq(Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()),
                eq(Instant.parse("2026-10-11T23:59:59.999Z").toEpochMilli())))
                .thenReturn(List.of());

        TradeHistoryRequest request = new TradeHistoryRequest();
        request.setPeriod("last-week");
        TradeHistoryView view = service.view(request);

        assertEquals("Last week (Mon–Sun)", view.totals().periodText());
        assertEquals(0, view.totals().roundTrips());
        assertEquals("—", view.totals().winRateText());
        assertTrue(view.rows().isEmpty());
    }

    @Test
    void view_resolvesYearToDateAndLastYear() {
        when(positions.findByStatusAndEntryTimeRange(
                eq("CLOSED"),
                eq(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()),
                eq(NOW.toEpochMilli())))
                .thenReturn(List.of());
        when(positions.findByStatusAndEntryTimeRange(
                eq("CLOSED"),
                eq(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli()),
                eq(Instant.parse("2025-12-31T23:59:59.999Z").toEpochMilli())))
                .thenReturn(List.of());

        TradeHistoryRequest ytd = new TradeHistoryRequest();
        ytd.setPeriod("ytd");
        assertEquals("Year to date", service.view(ytd).totals().periodText());

        TradeHistoryRequest lastYear = new TradeHistoryRequest();
        lastYear.setPeriod("last-year");
        assertEquals("Last year", service.view(lastYear).totals().periodText());
    }

    @Test
    void view_prefersTheCustomRangeAndLabelsIt() {
        when(positions.findByStatusAndEntryTimeRange(
                eq("CLOSED"),
                eq(Instant.parse("2026-10-07T09:00:00Z").toEpochMilli()),
                eq(Instant.parse("2026-10-14T14:55:59.999Z").toEpochMilli())))
                .thenReturn(List.of());

        TradeHistoryRequest request = new TradeHistoryRequest();
        request.setPeriod("last-week");
        request.setFromDate("2026-10-07");
        request.setFromTime("09:00");
        request.setToDate("2026-10-14");
        request.setToTime("14:55");
        TradeHistoryView view = service.view(request);

        assertEquals("7 Oct 09:00 → 14 Oct 14:55", view.totals().periodText());
        assertEquals("", view.filters().period());
        assertEquals("2026-10-07", view.filters().fromDate());
    }

    @Test
    void view_dateOnlyCustomRangeCoversWholeDays() {
        when(positions.findByStatusAndEntryTimeRange(
                eq("CLOSED"),
                eq(Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()),
                eq(Instant.parse("2026-10-08T23:59:59.999Z").toEpochMilli())))
                .thenReturn(List.of());

        TradeHistoryRequest request = new TradeHistoryRequest();
        request.setFromDate("2026-10-07");
        request.setToDate("2026-10-08");
        TradeHistoryView view = service.view(request);

        assertEquals("7 Oct 00:00 → 8 Oct 23:59", view.totals().periodText());
    }

    @Test
    void view_detailsCarryFeesAndOrderReferences() {
        LivePosition real = LivePosition.builder()
                .strategyId(STRATEGY).market("BTC-EUR").status("CLOSED")
                .quantity(0.00134403).entryPrice(74_403).entryFee(0.20)
                .entryTime(NOW.minusSeconds(90 * 60).toEpochMilli())
                .exitPrice(74_412.0).exitTime(NOW.minusSeconds(89 * 60).toEpochMilli())
                .profit(0.12).dryRun(false).build();
        stubPeriod(List.of(real));
        when(orders.findBySignalOpenTimeRange(anyLong(), anyLong()))
                .thenReturn(List.of(
                        order("BUY", real.getEntryTime(), "8f14e2c7-aaaa-bbbb-cccc-dddddddddddd", 0.20),
                        order("SELL", real.getExitTime(), "c92b71a0-1111-2222-3333-444444444444", 0.20)));

        TradeHistoryView view = service.view(new TradeHistoryRequest());

        TradeHistoryView.DetailsView details = view.rows().get(0).details();
        assertEquals("buy 8f14e2c7… · sell c92b71a0…", details.orderRefsText());
        assertEquals("fee €0,20", details.boughtFeeText());
        assertEquals("fee €0,20", details.soldFeeText());
        assertEquals("1 min 0 s", details.heldText());
        assertTrue(details.boughtText().contains("0,00134403 BTC @ €74.403,00"));
    }

    @Test
    void view_customRangeDefaultsItsOpenEndsAndShowsTheWholeDayTimes() {
        stubPeriod(List.of(position(STRATEGY, NOW.minusSeconds(2 * 3600).toEpochMilli(), 0.12, false)));

        TradeHistoryRequest fromOnly = new TradeHistoryRequest();
        fromOnly.setFromDate("2026-10-10");
        TradeHistoryView view = service.view(fromOnly);

        assertEquals("00:00", view.filters().fromTime());
        assertEquals("23:59", view.filters().toTime());
        ArgumentCaptor<Long> from = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> to = ArgumentCaptor.forClass(Long.class);
        verify(positions).findByStatusAndEntryTimeRange(eq("CLOSED"), from.capture(), to.capture());
        // The start runs from the chosen day's midnight, the open end through today.
        assertEquals(Instant.parse("2026-10-10T00:00:00Z").toEpochMilli(), from.getValue());
        assertEquals(Instant.parse("2026-10-15T23:59:59.999Z").toEpochMilli(), to.getValue());
    }

    @Test
    void view_detailsSayWhyDryRunsHaveNoReferences() {
        stubPeriod(List.of(position(STRATEGY, NOW.minusSeconds(2 * 3600).toEpochMilli(), 0.12, true)));

        TradeHistoryView view = service.view(new TradeHistoryRequest());

        assertEquals("none — dry run (simulated)", view.rows().get(0).details().orderRefsText());
    }

    @Test
    void view_dailyShowsRealTradesOnlyWithCumulative() {
        stubPeriod(List.of(
                position(STRATEGY, Instant.parse("2026-10-13T10:00:00Z").toEpochMilli(), -0.11, false),
                position(STRATEGY, Instant.parse("2026-10-14T10:00:00Z").toEpochMilli(), 0.12, false),
                position(STRATEGY, NOW.minusSeconds(2 * HOUR).toEpochMilli(), -0.50, true)));

        TradeHistoryView view = service.view(new TradeHistoryRequest());

        assertEquals(2, view.daily().size());
        assertEquals("13 Oct", view.daily().get(0).dayText());
        assertEquals(1, view.daily().get(0).roundTrips());
        assertEquals("−€0,11", view.daily().get(0).realisedText());
        assertEquals("−€0,11", view.daily().get(0).cumulativeText());
        assertEquals("14 Oct", view.daily().get(1).dayText());
        assertEquals("+€0,01", view.daily().get(1).cumulativeText());
        assertEquals("pos", view.daily().get(1).cumulativeClass());
    }

    @Test
    void zoneLabel_namesTheDisplayZone() {
        assertEquals("UTC", service.zoneLabel());
    }

    @Test
    void view_resolvesEveryPresetAndKeepsItInTheLinks() {
        for (String preset : List.of("1h", "2h", "4h", "8h", "24h", "3d", "7d", "14d", "30d",
                "last-week", "last-two-weeks", "last-month", "ytd", "last-year")) {
            stubPeriod(List.of());
            TradeHistoryRequest request = new TradeHistoryRequest();
            request.setPeriod(preset);

            TradeHistoryView view = service.view(request);

            assertFalse(view.totals().periodText().isBlank(), preset);
            assertTrue(view.headers().get(0).query().contains("period=" + preset), preset);
            assertEquals(preset, view.filters().period(), preset);
        }
    }

    @Test
    void view_sortsByEveryColumnInBothDirections() {
        for (String key : List.of("entry", "exit", "strategy", "type", "price", "quantity", "result")) {
            for (String dir : List.of("asc", "desc")) {
                stubPeriod(List.of(
                        position(STRATEGY, NOW.minusSeconds(3600).toEpochMilli(), 0.12, true),
                        position("b-strategy", NOW.minusSeconds(7200).toEpochMilli(), -0.11, false)));
                TradeHistoryRequest request = new TradeHistoryRequest();
                request.setSort(key);
                request.setDir(dir);

                TradeHistoryView view = service.view(request);

                assertEquals(2, view.rows().size(), key + " " + dir);
                TradeHistoryView.HeaderView active = view.headers().stream()
                        .filter(header -> header.query().contains("sort=" + key)).findFirst().orElseThrow();
                assertTrue(active.query().contains("sort=" + key), key);
            }
        }
    }

    @Test
    void view_coversDefensiveBranchesForMissingValues() {
        LivePosition incomplete = LivePosition.builder()
                .strategyId("short").market(null).status("CLOSED")
                .quantity(0.5).entryPrice(100).entryFee(null).entryTime(NOW.minusSeconds(30).toEpochMilli())
                .exitPrice(null).exitTime(null).profit(null).dryRun(false).build();
        stubPeriod(List.of(incomplete));
        when(orders.findBySignalOpenTimeRange(anyLong(), anyLong()))
                .thenReturn(List.of());

        TradeHistoryRequest request = new TradeHistoryRequest();
        request.setSort("exit");
        TradeHistoryView view = service.view(request);

        TradeHistoryView.TradeRowView row = view.rows().get(0);
        assertEquals("short", row.strategyId());
        assertEquals("—", row.exitText());
        assertEquals("€100,00 → —", row.priceText());
        assertEquals("+€0,00", row.resultText());
        assertEquals("fee —", row.details().boughtFeeText());
        assertEquals("—", row.details().soldText());
        assertEquals("fee —", row.details().soldFeeText());
        assertEquals("—", row.details().heldText());
        assertEquals("buy — · sell —", row.details().orderRefsText());
    }

    @Test
    void view_durationsUseHoursAndSeconds() {
        stubPeriod(List.of(
                heldPosition(NOW.minusSeconds(2 * 3600 + 5 * 60).toEpochMilli(), 2 * 3600 + 5 * 60),
                heldPosition(NOW.minusSeconds(4 * 3600).toEpochMilli(), 30)));

        TradeHistoryView view = service.view(new TradeHistoryRequest());

        List<String> held = view.rows().stream().map(row -> row.details().heldText()).toList();
        assertTrue(held.contains("2 h 5 min"), held.toString());
        assertTrue(held.contains("30 s"), held.toString());
    }

    @Test
    void view_coversParseAndOptionFallbacks() {
        stubPeriod(List.of(position(STRATEGY, NOW.minusSeconds(3600).toEpochMilli(), 0.12, true)));

        TradeHistoryRequest garbage = new TradeHistoryRequest();
        garbage.setPeriod("nonsense");
        garbage.setType("sideways");
        garbage.setResult("draws");
        garbage.setSize(999);
        garbage.setPage(99);
        TradeHistoryView view = service.view(garbage);

        assertEquals(1, view.rows().size());
        assertEquals(1, view.pager().page());
        assertEquals(10, view.pager().size());
        assertEquals("7d", view.filters().period());
        assertEquals("Last 7 days", view.totals().periodText());

        TradeHistoryRequest badDates = new TradeHistoryRequest();
        badDates.setFromDate("not-a-date");
        assertEquals("Last 7 days", service.view(badDates).totals().periodText());

        // A single date side no longer falls back to the preset: the open side takes its default.
        TradeHistoryRequest toOnly = new TradeHistoryRequest();
        toOnly.setToDate("2026-10-08");
        assertEquals("the start → 8 Oct 23:59", service.view(toOnly).totals().periodText());

        // A time that is present but unreadable follows the date rule: the preset stands in.
        TradeHistoryRequest badTime = new TradeHistoryRequest();
        badTime.setFromDate("2026-10-07");
        badTime.setFromTime("xyz");
        badTime.setToDate("2026-10-08");
        badTime.setToTime("25:99");
        assertEquals("Last 7 days", service.view(badTime).totals().periodText());

        // A range that runs backwards (a start in the future) falls back to the preset rather than
        // asking for an impossible pair.
        TradeHistoryRequest future = new TradeHistoryRequest();
        future.setFromDate("2027-01-01");
        assertEquals("Last 7 days", service.view(future).totals().periodText());

        // And a properly formed custom range still resolves, times and all.
        TradeHistoryRequest fine = new TradeHistoryRequest();
        fine.setFromDate("2026-10-07");
        fine.setFromTime("09:30");
        fine.setToDate("2026-10-08");
        fine.setToTime("14:00");
        assertEquals("7 Oct 09:30 → 8 Oct 14:00", service.view(fine).totals().periodText());
    }

    @Test
    void view_keepsAFilteredStrategyInTheOptionsEvenWithoutRows() {
        stubPeriod(List.of());
        TradeHistoryRequest request = new TradeHistoryRequest();
        request.setStrategy(STRATEGY);

        TradeHistoryView view = service.view(request);

        assertEquals(1, view.filters().strategyOptions().size());
        assertEquals(STRATEGY, view.filters().strategyOptions().get(0).value());
        assertEquals("6ace5dbf", view.filters().strategyOptions().get(0).label());
    }

    @Test
    void constructorsAcceptABlankOrMissingTimeZone() {
        assertFalse(new TradeHistoryService(positions, orders, (String) null).zoneLabel().isBlank());
        assertFalse(new TradeHistoryService(positions, orders, "  ").zoneLabel().isBlank());
    }

    private static LivePosition heldPosition(long entryMillis, long heldSeconds) {
        return LivePosition.builder()
                .strategyId(STRATEGY).market("BTC-EUR").status("CLOSED")
                .quantity(0.00134403).entryPrice(74_403).entryFee(0.20).entryTime(entryMillis)
                .exitPrice(74_412.0).exitTime(entryMillis + heldSeconds * 1000).profit(0.12)
                .dryRun(true).build();
    }

    private void stubPeriod(List<LivePosition> result) {
        when(positions.findByStatusAndEntryTimeRange(
                eq("CLOSED"), anyLong(), anyLong())).thenReturn(result);
    }

    private static LivePosition position(String strategyId, long entryMillis, double profit, boolean dryRun) {
        return LivePosition.builder()
                .strategyId(strategyId).market("BTC-EUR").status("CLOSED")
                .quantity(0.00134403).entryPrice(74_403).entryFee(0.20).entryTime(entryMillis)
                .exitPrice(74_412.0).exitTime(entryMillis + 60_000).profit(profit).dryRun(dryRun).build();
    }

    private static LiveOrder order(String side, Long signalOpenTime, String orderId, double fee) {
        return LiveOrder.builder()
                .strategyId(STRATEGY).market("BTC-EUR").side(side).status("FILLED").dryRun(false)
                .signalOpenTime(signalOpenTime).orderId(orderId).fee(fee)
                .createdTime(NOW).build();
    }
}
