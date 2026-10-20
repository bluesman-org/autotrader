package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.service.LedgerService;
import nl.jimkaplan.autotrader.strategy.service.TradeHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the engine-trades tab and its table fragment, so a broken expression fails here instead
 * of in the browser. The property overrides stand in for the environment variables the
 * application.yml placeholders expect, which a test run does not have; the display zone is pinned
 * so the rendered times are deterministic.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = HistoryUiController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk=",
        "autotrader.ui.time-zone=UTC"
})
class HistoryUiControllerRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TradeHistoryService tradeHistoryService;

    @MockitoBean
    private LedgerService ledgerService;

    @Test
    void pageRendersTheTableTotalsAndDailyBreakdown() throws Exception {
        when(tradeHistoryService.view(any())).thenReturn(view());
        when(tradeHistoryService.zoneLabel()).thenReturn("UTC");

        mockMvc.perform(get("/ui/history"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Account history")))
                .andExpect(content().string(containsString("Times are local time (UTC)")))
                .andExpect(content().string(containsString("Engine trades")))
                .andExpect(content().string(containsString("6ace5dbf")))
                .andExpect(content().string(containsString("DRY RUN")))
                .andExpect(content().string(containsString("14 Oct 2026 14:10")))
                .andExpect(content().string(containsString("+€0,13")))
                .andExpect(content().string(containsString("none — dry run (simulated)")))
                .andExpect(content().string(containsString("sort=result")))
                .andExpect(content().string(containsString("Showing")))
                .andExpect(content().string(containsString("Round trips")))
                .andExpect(content().string(containsString("−€16,68")))
                .andExpect(content().string(containsString("24%")))
                .andExpect(content().string(containsString("Last 7 days")))
                .andExpect(content().string(containsString("Realised P&amp;L by day")))
                .andExpect(content().string(containsString("dry-runs excluded")))
                .andExpect(content().string(containsString("13 Oct")))
                .andExpect(content().string(containsString("Older →")));
    }

    @Test
    void fragmentParameterRendersTheTableAlone() throws Exception {
        when(tradeHistoryService.view(any())).thenReturn(view());

        mockMvc.perform(get("/ui/history").param("fragment", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("6ace5dbf")))
                .andExpect(content().string(not(containsString("Account history"))));
    }

    @Test
    void pageShowsTheEmptyStateWhenNothingMatches() throws Exception {
        when(tradeHistoryService.view(any())).thenReturn(emptyView());
        when(tradeHistoryService.zoneLabel()).thenReturn("UTC");

        mockMvc.perform(get("/ui/history"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No round trips match these filters.")))
                .andExpect(content().string(containsString("No real trades in this period.")));
    }

    private static TradeHistoryView view() {
        TradeHistoryView.TradeRowView row = new TradeHistoryView.TradeRowView(1, "6ace5dbf", "schip hue-0",
                "hue-0", "DRY RUN", "dry", "14 Oct 2026 14:10", "14 Oct 2026 14:11", "73,990 → 74,380",
                "0.00135153", "+€0,13", "pos",
                new TradeHistoryView.DetailsView("14 Oct 2026 14:10 · 0,00135153 BTC @ €73.990,00", "fee €0,20",
                        "14 Oct 2026 14:11 · 0,00135153 BTC @ €74.380,00", "fee €0,20", "1 min 3 s",
                        "none — dry run (simulated)"));
        List<TradeHistoryView.HeaderView> headers = List.of(
                new TradeHistoryView.HeaderView("Entry", "left", "?sort=entry&dir=desc", true, "▼"),
                new TradeHistoryView.HeaderView("Result", "", "?sort=result&dir=asc", false, ""));
        TradeHistoryView.FiltersView filters = new TradeHistoryView.FiltersView("", "", "", "", "7d",
                "", "", "", "", "7d",
                List.of(new TradeHistoryView.Option("6ace5dbf7b4e0870883058cb", "6ace5dbf")),
                List.of(new TradeHistoryView.Option("BTC-EUR", "BTC-EUR")),
                List.of(new TradeHistoryView.Option("7d", "Last 7 days")));
        TradeHistoryView.PagerView pager = new TradeHistoryView.PagerView(1, 1, 41, 1, 5, 10, null, "?page=2",
                List.of(new TradeHistoryView.SizeOption("10", "?size=10", true)));
        TradeHistoryView.TotalsView totals = new TradeHistoryView.TotalsView(41, "−€16,68", "neg", "24%",
                "Last 7 days");
        List<TradeHistoryView.DailyView> daily = List.of(
                new TradeHistoryView.DailyView("13 Oct 2026", 1, "−€0,11", "neg", "−€0,11", "neg"));
        return new TradeHistoryView(List.of(row), headers, filters, pager, totals, daily);
    }

    private static TradeHistoryView emptyView() {
        TradeHistoryView.FiltersView filters = new TradeHistoryView.FiltersView("", "", "", "", "7d",
                "", "", "", "", "7d", List.of(), List.of(), List.of());
        TradeHistoryView.PagerView pager = new TradeHistoryView.PagerView(0, 0, 0, 1, 1, 10, null, null,
                List.of());
        TradeHistoryView.TotalsView totals = new TradeHistoryView.TotalsView(0, "+€0,00", "pos", "—",
                "Last 7 days");
        return new TradeHistoryView(List.of(), List.of(), filters, pager, totals, List.of());
    }

    @Test
    void theLedgerTabRendersPricesTotalsAndOrders() throws Exception {
        when(ledgerService.view(any())).thenReturn(ledgerView());
        when(ledgerService.zoneLabel()).thenReturn("UTC");

        mockMvc.perform(get("/ui/history").param("tab", "ledger"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Account history")))
                .andExpect(content().string(containsString("Prices and P&amp;L values refresh every 10 seconds")))
                .andExpect(content().string(containsString("Bitvavo ledger")))
                .andExpect(content().string(containsString("Current price of")))
                .andExpect(content().string(containsString("BTC-EUR")))
                .andExpect(content().string(containsString("€75.600,00")))
                .andExpect(content().string(containsString("€75.500,00")))
                .andExpect(content().string(containsString("Total fees")))
                .andExpect(content().string(containsString("€0,23")))
                .andExpect(content().string(containsString("0,0015 BTC")))
                .andExpect(content().string(containsString("Balance after")))
                .andExpect(content().string(containsString("counts only filled orders")))
                .andExpect(content().string(containsString("14 Oct 2026 09:00")))
                .andExpect(content().string(containsString("+€0,60")))
                .andExpect(content().string(containsString("side-sell")))
                .andExpect(content().string(containsString("fills (1)")))
                .andExpect(content().string(containsString(">Awaiting trigger<")))
                .andExpect(content().string(containsString("awaiting trigger")))
                // The type and status filters are checkbox groups whose choices are marked.
                .andExpect(content().string(containsString("type=\"checkbox\" name=\"type\"")))
                .andExpect(content().string(containsString("type=\"checkbox\" name=\"status\"")))
                .andExpect(content().string(containsString("value=\"takeProfitLimit\" checked=\"checked\"")))
                .andExpect(content().string(containsString("value=\"awaitingTrigger\" checked=\"checked\"")))
                .andExpect(content().string(containsString("trigger €73.162,00 · limit €75.364,00")))
                .andExpect(content().string(containsString("09:00:00")))
                // The Today button sets the range's end to now, and the caption carries the P&L rules.
                .andExpect(content().string(containsString("data-today")))
                .andExpect(content().string(containsString(">Today</button>")))
                .andExpect(content().string(containsString("the sell that closes it carries it")))
                .andExpect(content().string(containsString("shows only when the range ends today")))
                .andExpect(content().string(containsString("wireHistoryContent('ledger', 10)")));
    }

    @Test
    void theLedgerExplainsWhyUnrealisedIsEmpty() throws Exception {
        when(ledgerService.view(any())).thenReturn(ledgerView(
                "Unrealised needs today as the end date — end the range with today to see it.", "—"));

        mockMvc.perform(get("/ui/history").param("tab", "ledger"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("end the range with today to see it")));
    }

    @Test
    void theAllPairsLedgerRendersWithoutAPriceCard() throws Exception {
        when(ledgerService.view(any())).thenReturn(allPairsLedgerView());

        mockMvc.perform(get("/ui/history").param("tab", "ledger").param("market", "all"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("All pairs")))
                .andExpect(content().string(containsString(">Amount</th>")))
                .andExpect(content().string(not(containsString("Current price of"))));
    }

    @Test
    void theLedgerFragmentRendersTheContentAlone() throws Exception {
        when(ledgerService.view(any())).thenReturn(ledgerView());

        mockMvc.perform(get("/ui/history").param("tab", "ledger").param("fragment", "ledger"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Current price of")))
                .andExpect(content().string(containsString("+€0,60")))
                .andExpect(content().string(not(containsString("Account history"))));
    }

    private static LedgerView ledgerView() {
        return ledgerView("", "+€1,28");
    }

    private static LedgerView ledgerView(String unrealisedHint, String unrealisedText) {
        LedgerView.FillRowView fill = new LedgerView.FillRowView("14 Oct 2026 09:00:00", "0.00050000", "€76.000,00",
                "€0,0800", "taker", "+€37,92", "pos");
        LedgerView.OrderRowView row = new LedgerView.OrderRowView(1, "14 Oct 2026 09:00", "SELL", "side-sell",
                "takeProfitLimit", "BTC-EUR", "0.00050000", "€76.000,00", "awaiting trigger", "",
                "trigger €73.162,00 · limit €75.364,00", "0.0015", "€0,08",
                "+€37,92", "pos", 1, "+€0,60", "pos", List.of(fill), "");
        LedgerView.FiltersView filters = new LedgerView.FiltersView("", "BTC-EUR", "Amount (BTC)",
                List.of("takeProfitLimit"), List.of("awaitingTrigger"), "orders",
                "7d", "", "", "", "", "7d",
                List.of(new TradeHistoryView.Option("BTC-EUR", "BTC-EUR")),
                List.of(new TradeHistoryView.Option("takeProfitLimit", "takeProfitLimit")),
                List.of(new TradeHistoryView.Option("awaitingTrigger", "Awaiting trigger")),
                List.of(new TradeHistoryView.Option("7d", "Last 7 days")));
        LedgerView.TotalsView totals = new LedgerView.TotalsView("Last 7 days", 1, "€0,23", "+€0,60",
                "pos", unrealisedText, "pos", "0,0015 BTC", unrealisedHint, "");
        TradeHistoryView.PagerView pager = new TradeHistoryView.PagerView(1, 1, 1, 1, 1, 10, null, null,
                List.of(new TradeHistoryView.SizeOption("10", "?tab=ledger&size=10", true)));
        return new LedgerView(null, new LedgerView.PriceView("BTC-EUR", "€75.600,00", "€75.500,00"),
                filters, totals, List.of(row), pager, false, List.of(), pager);
    }

    private static LedgerView allPairsLedgerView() {
        LedgerView.OrderRowView row = new LedgerView.OrderRowView(1, "14 Oct 2026 09:00", "SELL", "side-sell",
                "market", "BTC-EUR", "0.00050000", "€76.000,00", "filled", "ok", "", "0.0015",
                "€0,08", "+€37,92", "pos", 1, "+€0,60", "pos", List.of(), "");
        LedgerView.FiltersView filters = new LedgerView.FiltersView("", "all", "Amount",
                List.of(), List.of(), "orders", "7d", "", "", "", "", "7d",
                List.of(new TradeHistoryView.Option("all", "All pairs"),
                        new TradeHistoryView.Option("BTC-EUR", "BTC-EUR")),
                List.of(), List.of(), List.of(new TradeHistoryView.Option("7d", "Last 7 days")));
        LedgerView.TotalsView totals = new LedgerView.TotalsView("Last 7 days", 1, "€0,23", "+€0,60",
                "pos", "—", "", "€1.000,00", "Nothing is held, so there is no unrealised result.", "");
        TradeHistoryView.PagerView pager = new TradeHistoryView.PagerView(1, 1, 1, 1, 1, 10, null, null,
                List.of(new TradeHistoryView.SizeOption("10", "?tab=ledger&size=10", true)));
        return new LedgerView(null, null, filters, totals, List.of(row), pager, false, List.of(), pager);
    }
}
