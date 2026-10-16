package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.service.LedgerService;
import nl.jimkaplan.autotrader.strategy.service.TradeHistoryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The account history page with its two tabs: the engine's closed round trips and the Bitvavo
 * ledger of the account the engine trades on. Sorting, paging, filtering and switching the ledger's
 * view swap the content in place; the links carry the whole state, so a reload or a shared URL
 * shows the same view.
 */
@Controller
@RequestMapping("/ui/history")
public class HistoryUiController {

    private final TradeHistoryService tradeHistoryService;
    private final LedgerService ledgerService;

    public HistoryUiController(TradeHistoryService tradeHistoryService, LedgerService ledgerService) {
        this.tradeHistoryService = tradeHistoryService;
        this.ledgerService = ledgerService;
    }

    /**
     * @param request       The engine-trades tab's filter, sort and paging state
     * @param ledgerRequest The ledger tab's filter, view and paging state
     * @param tab           {@code ledger} selects the ledger tab; anything else the engine trades
     * @param fragment      When present, render only the tab's content (the in-place swap)
     * @param model         The view model
     * @return The page, or the tab's content fragment
     */
    @GetMapping
    public String page(@ModelAttribute TradeHistoryRequest request,
                       @ModelAttribute LedgerRequest ledgerRequest,
                       @RequestParam(required = false) String tab,
                       @RequestParam(required = false) String fragment,
                       Model model) {
        boolean ledger = "ledger".equalsIgnoreCase(tab);
        model.addAttribute("ledgerTab", ledger);
        if (ledger) {
            model.addAttribute("ledger", ledgerService.view(ledgerRequest));
            model.addAttribute("timeZoneLabel", ledgerService.zoneLabel());
        } else {
            model.addAttribute("history", tradeHistoryService.view(request));
            model.addAttribute("timeZoneLabel", tradeHistoryService.zoneLabel());
        }
        if (fragment != null) {
            return ledger ? "history/ledger" : "history/trades";
        }
        return "history/index";
    }
}
