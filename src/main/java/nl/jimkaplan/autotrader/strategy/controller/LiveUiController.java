package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import nl.jimkaplan.autotrader.strategy.service.LiveViewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The live page: one card per strategy with its state, open position and recent orders, refreshed
 * in place every few seconds. Start and stop are the only writes; a failing one is logged and the
 * page simply shows the unchanged state.
 */
@Controller
@RequestMapping("/ui/live")
public class LiveUiController {

    private static final Logger log = LoggerFactory.getLogger(LiveUiController.class);

    private final LiveViewService liveViewService;
    private final LiveStrategyService liveStrategyService;

    public LiveUiController(LiveViewService liveViewService, LiveStrategyService liveStrategyService) {
        this.liveViewService = liveViewService;
        this.liveStrategyService = liveStrategyService;
    }

    /**
     * @param model The cards and the display zone's label
     * @return The live page
     */
    @GetMapping
    public String page(Model model) {
        model.addAttribute("cards", liveViewService.cards());
        model.addAttribute("timeZoneLabel", liveViewService.zoneLabel());
        return "live/index";
    }

    /**
     * @param model The cards
     * @return The cards alone, for the page's in-place refresh
     */
    @GetMapping("/cards")
    public String cards(Model model) {
        model.addAttribute("cards", liveViewService.cards());
        return "live/cards";
    }

    /**
     * @param id The strategy ID
     * @return A redirect back to the live page
     */
    @PostMapping("/{id}/start")
    public String start(@PathVariable String id) {
        return toggle(id, true);
    }

    /**
     * @param id The strategy ID
     * @return A redirect back to the live page
     */
    @PostMapping("/{id}/stop")
    public String stop(@PathVariable String id) {
        return toggle(id, false);
    }

    private String toggle(String id, boolean start) {
        try {
            if (start) {
                liveStrategyService.start(id);
            } else {
                liveStrategyService.stop(id);
            }
        } catch (RuntimeException e) {
            // The strategy id is request-supplied, so it stays out of the log line (CRLF-injection
            // hygiene, as in BacktestService); the page shows whether the state changed.
            log.warn("Could not {} a live strategy: {}", start ? "start" : "stop",
                    e.getClass().getSimpleName());
        }
        return "redirect:/ui/live";
    }
}
