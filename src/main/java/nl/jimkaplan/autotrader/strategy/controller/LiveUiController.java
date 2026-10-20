package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.service.MarketService;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunEntry;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.service.BacktestService;
import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import nl.jimkaplan.autotrader.strategy.service.LiveViewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The live page — one card per strategy with its state, open position and recent orders — and the
 * form that creates, edits, duplicates and deletes them. Start and stop stay on the cards; the
 * writes that can fail show the error back on the page.
 */
@Controller
@RequestMapping("/ui/live")
public class LiveUiController {

    private static final Logger log = LoggerFactory.getLogger(LiveUiController.class);

    private final LiveViewService liveViewService;
    private final LiveStrategyService liveStrategyService;
    private final BacktestService backtestService;
    private final MarketService marketService;

    public LiveUiController(LiveViewService liveViewService, LiveStrategyService liveStrategyService,
                            BacktestService backtestService, MarketService marketService) {
        this.liveViewService = liveViewService;
        this.liveStrategyService = liveStrategyService;
        this.backtestService = backtestService;
        this.marketService = marketService;
    }

    /**
     * @param model The cards, the display zone's label and its IANA id (for the refresh stamp)
     * @return The live page
     */
    @GetMapping
    public String page(Model model) {
        model.addAttribute("cards", liveViewService.cards());
        model.addAttribute("timeZoneLabel", liveViewService.zoneLabel());
        model.addAttribute("timeZoneId", liveViewService.zoneId());
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
     * The create form. It can be prefilled from a stored strategy (copy or real copy) or from one
     * ranked backtest entry.
     *
     * @param run    A stored backtest run to prefill from
     * @param entry  The 1-based entry number within that run
     * @param copy   A stored strategy to prefill from
     * @param real   When copying, create the real copy (dry-run off, name suffixed {@code -REAL})
     * @param model  The form model
     * @return The form
     */
    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) String run,
                          @RequestParam(required = false) Integer entry,
                          @RequestParam(required = false) String copy,
                          @RequestParam(defaultValue = "false") boolean real,
                          Model model) {
        LiveStrategyForm form = LiveStrategyForm.defaults();
        if (copy != null && !copy.isBlank()) {
            LiveStrategy source = require(copy);
            form = LiveStrategyForm.fromStrategy(source);
            String name = source.getName() == null || source.getName().isBlank()
                    ? source.getMarket() + " " + source.getInterval() : source.getName();
            form.setName(real ? name + "-REAL" : name + " copy");
            form.setDryRun(!real);
        } else if (run != null && !run.isBlank()) {
            form = fromBacktest(run, entry);
        }
        List<String> markets = marketChoices(form);
        if (form.getMarket() == null || (!markets.contains(form.getMarket()) && !markets.isEmpty())) {
            if (!markets.isEmpty()) {
                form.setMarket(markets.get(0));
            }
        }
        return renderForm(model, form, null, false, false, null);
    }

    /**
     * @param form  The submitted form
     * @param model The form model, on a validation error
     * @return A redirect to the live page, or the form with the error
     */
    @PostMapping
    public String create(@ModelAttribute("form") LiveStrategyForm form, Model model) {
        try {
            LiveStrategy strategy = liveStrategyService.create(form.toRequest());
            log.info("UI created live strategy {}", strategy.getId());
            return "redirect:/ui/live";
        } catch (IllegalArgumentException e) {
            return renderForm(model, form, null, false, false, e.getMessage());
        }
    }

    /**
     * @param id    The strategy ID
     * @param model The form model
     * @return The edit form, or a redirect when the strategy is unknown
     */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable String id, Model model) {
        LiveStrategy strategy = require(id);
        boolean started = started(strategy);
        boolean running = liveStrategyService.isRunning(id);
        return renderForm(model, LiveStrategyForm.fromStrategy(strategy), id, started, true,
                running ? "Stop the strategy before editing it." : null);
    }

    /**
     * @param id    The strategy ID
     * @param form  The submitted form
     * @param model The form model, on a validation error
     * @return A redirect to the live page, or the form with the error
     */
    @PostMapping("/{id}")
    public String update(@PathVariable String id, @ModelAttribute("form") LiveStrategyForm form,
                         Model model) {
        LiveStrategy strategy = require(id);
        try {
            liveStrategyService.update(id, form.toRequest());
            log.info("UI updated live strategy {}", id);
            return "redirect:/ui/live";
        } catch (IllegalArgumentException e) {
            return renderForm(model, form, id, started(strategy), true, e.getMessage());
        }
    }

    /**
     * Delete a strategy that never started, or archive one that has run.
     *
     * @param id                 The strategy ID
     * @param redirectAttributes Carries the outcome back to the live page
     * @return A redirect to the live page
     */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes redirectAttributes) {
        try {
            boolean archived = liveStrategyService.delete(id);
            redirectAttributes.addFlashAttribute("notice", archived
                    ? "The strategy was archived; its orders and trades stay in the history."
                    : "The strategy was deleted.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/ui/live";
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

    private LiveStrategyForm fromBacktest(String runId, Integer entryNumber) {
        BacktestRun run = backtestService.get(runId)
                .orElseThrow(() -> new NoSuchElementException("Backtest run not found: " + runId));
        List<BacktestRunEntry> entries = run.getEntries() == null ? List.of() : run.getEntries();
        int number = entryNumber == null ? 1 : entryNumber;
        if (number < 1 || number > entries.size()) {
            throw new IllegalArgumentException("that backtest entry does not exist");
        }
        LiveStrategyForm form = LiveStrategyForm.fromBacktest(run, entries.get(number - 1));
        form.setDryRun(true);
        return form;
    }

    private String renderForm(Model model, LiveStrategyForm form, String strategyId, boolean started,
                              boolean editing, String error) {
        model.addAttribute("form", form);
        model.addAttribute("editing", editing);
        model.addAttribute("strategyId", strategyId);
        model.addAttribute("started", started);
        model.addAttribute("running", strategyId != null && liveStrategyService.isRunning(strategyId));
        model.addAttribute("error", error);
        model.addAttribute("intervals", CandleInterval.values());
        model.addAttribute("operators", LogicalOperator.values());
        model.addAttribute("conditions", RuleCondition.values());
        model.addAttribute("markets", marketChoices(form));
        return "live/form";
    }

    private List<String> marketChoices(LiveStrategyForm form) {
        List<String> markets = new ArrayList<>(marketService.selectable());
        String selected = form == null ? null : form.getMarket();
        if (selected != null && !selected.isBlank() && !markets.contains(selected)) {
            markets.add(selected);
        }
        return markets;
    }

    private static boolean started(LiveStrategy strategy) {
        return strategy.getLastStartedTime() != null || strategy.getSeriesStartTime() > 0;
    }

    private LiveStrategy require(String id) {
        return liveStrategyService.get(id)
                .orElseThrow(() -> new NoSuchElementException("Live strategy not found: " + id));
    }
}
