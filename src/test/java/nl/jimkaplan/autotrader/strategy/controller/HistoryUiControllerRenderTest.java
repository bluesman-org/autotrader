package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.service.TradeHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
                .andExpect(content().string(containsString("14 Oct 14:10")))
                .andExpect(content().string(containsString("+€0.13")))
                .andExpect(content().string(containsString("none — dry run (simulated)")))
                .andExpect(content().string(containsString("sort=result")))
                .andExpect(content().string(containsString("Showing")))
                .andExpect(content().string(containsString("Round trips")))
                .andExpect(content().string(containsString("−€16.68")))
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
                "hue-0", "DRY RUN", "dry", "14 Oct 14:10", "14 Oct 14:11", "73,990 → 74,380",
                "0.00135153", "+€0.13", "pos",
                new TradeHistoryView.DetailsView("14 Oct 14:10 · 0.00135153 BTC @ €73,990.00", "fee €0.20",
                        "14 Oct 14:11 · 0.00135153 BTC @ €74,380.00", "fee €0.20", "1 min 3 s",
                        "none — dry run (simulated)"));
        List<TradeHistoryView.HeaderView> headers = List.of(
                new TradeHistoryView.HeaderView("Entry", "left", "?sort=entry&dir=desc", true, "▼"),
                new TradeHistoryView.HeaderView("Result", "", "?sort=result&dir=asc", false, ""));
        TradeHistoryView.FiltersView filters = new TradeHistoryView.FiltersView("", "", "", "7d",
                "", "", "", "",
                List.of(new TradeHistoryView.Option("6ace5dbf7b4e0870883058cb", "6ace5dbf")),
                List.of(new TradeHistoryView.Option("7d", "Last 7 days")));
        TradeHistoryView.PagerView pager = new TradeHistoryView.PagerView(1, 1, 41, 1, 5, 10, null, "?page=2",
                List.of(new TradeHistoryView.SizeOption("10", "?size=10", true)));
        TradeHistoryView.TotalsView totals = new TradeHistoryView.TotalsView(41, "−€16.68", "neg", "24%",
                "Last 7 days");
        List<TradeHistoryView.DailyView> daily = List.of(
                new TradeHistoryView.DailyView("13 Oct", 1, "−€0.11", "neg", "−€0.11", "neg"));
        return new TradeHistoryView(List.of(row), headers, filters, pager, totals, daily);
    }

    private static TradeHistoryView emptyView() {
        TradeHistoryView.FiltersView filters = new TradeHistoryView.FiltersView("", "", "", "7d",
                "", "", "", "", List.of(), List.of());
        TradeHistoryView.PagerView pager = new TradeHistoryView.PagerView(0, 0, 0, 1, 1, 10, null, null,
                List.of());
        TradeHistoryView.TotalsView totals = new TradeHistoryView.TotalsView(0, "+€0.00", "pos", "—",
                "Last 7 days");
        return new TradeHistoryView(List.of(), List.of(), filters, pager, totals, List.of());
    }
}
