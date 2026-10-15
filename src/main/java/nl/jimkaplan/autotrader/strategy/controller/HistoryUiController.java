package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.service.TradeHistoryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The account history page, starting with the engine-trades tab: the closed round trips with their
 * filters, sorting and paging. Sorting, paging and applying filters swap the table in place; the
 * links carry the whole state, so a reload or a shared URL shows the same view.
 */
@Controller
@RequestMapping("/ui/history")
public class HistoryUiController {

    private final TradeHistoryService tradeHistoryService;

    public HistoryUiController(TradeHistoryService tradeHistoryService) {
        this.tradeHistoryService = tradeHistoryService;
    }

    /**
     * @param request  The filter, sort and paging state, bound from the query string
     * @param fragment When present, render only the table (the in-place swap)
     * @param model    The view model
     * @return The page, or the table fragment
     */
    @GetMapping
    public String page(@ModelAttribute TradeHistoryRequest request,
                       @RequestParam(required = false) String fragment,
                       Model model) {
        model.addAttribute("history", tradeHistoryService.view(request));
        model.addAttribute("timeZoneLabel", tradeHistoryService.zoneLabel());
        return fragment != null ? "history/trades" : "history/index";
    }
}
