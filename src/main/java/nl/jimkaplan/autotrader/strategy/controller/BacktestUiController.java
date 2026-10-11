package nl.jimkaplan.autotrader.strategy.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.strategy.backtest.RankingMetric;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.service.BacktestService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * The in-app UI for backtests: configure and run a sweep, and browse the ranked results.
 */
@Slf4j
@Controller
@RequestMapping("/ui/backtests")
@RequiredArgsConstructor
public class BacktestUiController {

    /**
     * How many recent runs the form page lists.
     */
    static final int RECENT_LIMIT = 10;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final BacktestService backtestService;

    /**
     * The configuration form and the most recent runs.
     *
     * @param model The model; carries a flashed form after an invalid submission
     * @return The form view
     */
    @GetMapping
    public String form(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", BacktestRunForm.defaults());
        }
        populate(model);
        return "backtests/form";
    }

    /**
     * Run the configured sweep and show its ranked results.
     *
     * @param form  The submitted form
     * @param model The model, reused with the error when the request is rejected
     * @return A redirect to the stored run, or the form again with the error
     */
    @PostMapping
    public String run(@ModelAttribute("form") BacktestRunForm form, Model model) {
        try {
            BacktestRun run = backtestService.run(form.toRequest());
            log.info("UI ran backtest {}", run.getId());
            return "redirect:/ui/backtests/" + run.getId();
        } catch (IllegalArgumentException | RestClientException ex) {
            log.warn("Backtest form rejected: {}", ex.getMessage());
            model.addAttribute("error", ex.getMessage());
            populate(model);
            return "backtests/form";
        }
    }

    /**
     * A stored run and its ranked entries.
     *
     * @param id                 The run ID
     * @param model              The model
     * @param redirectAttributes Carries the error back to the form when the run is unknown
     * @return The run view, or a redirect to the form
     */
    @GetMapping("/{id}")
    public String runDetail(@PathVariable String id, Model model, RedirectAttributes redirectAttributes) {
        Optional<BacktestRun> storedRun = backtestService.get(id);
        if (storedRun.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "No backtest run with ID " + id);
            return "redirect:/ui/backtests";
        }
        BacktestRun run = storedRun.get();
        model.addAttribute("run", run);
        model.addAttribute("runAtText", TIMESTAMP.format(run.getRunAt()));
        model.addAttribute("rangeText", rangeText(run));
        return "backtests/run";
    }

    private void populate(Model model) {
        model.addAttribute("intervals", CandleInterval.values());
        model.addAttribute("rankingMetrics", RankingMetric.values());
        model.addAttribute("conditions", RuleCondition.values());
        model.addAttribute("operators", LogicalOperator.values());
        model.addAttribute("runs", recentViews());
    }

    private List<RunSummaryView> recentViews() {
        return backtestService.recent(RECENT_LIMIT).stream()
                .map(run -> new RunSummaryView(run, TIMESTAMP.format(run.getRunAt()), rangeText(run)))
                .toList();
    }

    private static String rangeText(BacktestRun run) {
        return DATE.format(Instant.ofEpochMilli(run.getFromTime()))
                + " → " + DATE.format(Instant.ofEpochMilli(run.getToTime()));
    }
}
