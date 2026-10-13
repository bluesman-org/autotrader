package nl.jimkaplan.autotrader.strategy.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestEntryTrades;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.service.BacktestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Controller for running strategy backtests and reading their ranked results.
 */
@Slf4j
@RestController
@RequestMapping("/api/backtests")
@RequiredArgsConstructor
@Tag(name = "Backtests", description = "API for running strategy backtests over stored candles")
public class BacktestController {

    private static final int MAX_RECENT_LIMIT = 100;

    private final BacktestService backtestService;

    /**
     * Run a backtest sweep over the requested range and store the ranked outcome.
     *
     * @param request The sweep request; only market, interval and range are required
     * @return The stored run, including the best ranked entries
     */
    @Operation(
            summary = "Run a backtest sweep",
            description = "Fetches the requested candles, sweeps the entry×exit rule matrices and the " +
                          "indicator parameter grids (at most 5000 combinations), optimises on the earlier " +
                          "part of the range and verifies on the held-out newer part, then stores the top " +
                          "ranked entries. Fields left out fall back to their defaults (1000 EUR capital, " +
                          "100 EUR per buy, 0.20% fee per side, 200-candle warm-up, 0.7 split, " +
                          "TOTAL_RETURN ranking, 12/26/9 MACD, 0.02/0.2 SAR, a MACD bullish crossover entry " +
                          "and a bearish SAR crossover exit). Ranges covering more than 50,000 candles are " +
                          "rejected."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Backtest run completed and stored",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BacktestRun.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request: missing or malformed fields, no candles for the range, " +
                                  "more than 5000 combinations, or a range over the 50,000-candle maximum",
                    content = @Content
            )
    })
    @PostMapping
    public ResponseEntity<BacktestRun> run(@RequestBody BacktestRunRequest request) {
        log.info("Received request to run a backtest");
        BacktestRun run = backtestService.run(request);
        log.info("Backtest run {} stored", run.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(run);
    }

    /**
     * Read a stored backtest run.
     *
     * @param id The run ID
     * @return The stored run
     */
    @Operation(summary = "Get a backtest run", operationId = "getBacktestRun",
            description = "Returns a stored backtest run and its ranked entries.")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Run found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BacktestRun.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "No run with that ID",
                    content = @Content
            )
    })
    @GetMapping("/{id}")
    public ResponseEntity<BacktestRun> get(
            @Parameter(description = "ID of the backtest run", required = true)
            @PathVariable String id) {
        return backtestService.get(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NoSuchElementException("Backtest run not found: " + id));
    }

    /**
     * Re-simulate one stored entry and return its individual trades.
     *
     * @param id          The run ID
     * @param entryNumber The entry's number in the ranked table, starting at 1
     * @return The entry's train and validate trades and skipped signals
     */
    @Operation(
            summary = "Get one entry's individual trades",
            description = "Re-simulates the stored entry over the same market, range, rules, parameters and " +
                          "split, deterministically, and returns the stored run, its recomputed train and " +
                          "validate trades and skipped signals, and whether the candle history changed " +
                          "since the run. A run stores only metrics, so the trades are recomputed on " +
                          "demand. Entries are numbered as shown in the ranked table, starting at 1."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Trades returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BacktestEntryTrades.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "entryNumber is out of range",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "No run with that ID",
                    content = @Content
            )
    })
    @GetMapping("/{id}/entries/{entryNumber}/trades")
    public ResponseEntity<BacktestEntryTrades> trades(
            @Parameter(description = "ID of the backtest run", required = true)
            @PathVariable String id,
            @Parameter(description = "Entry number in the ranked table (1-based)", required = true)
            @PathVariable int entryNumber) {
        return backtestService.trades(id, entryNumber)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NoSuchElementException("Backtest run not found: " + id));
    }

    /**
     * List the most recent runs, newest first.
     *
     * @param limit Maximum number of runs to return (1-100)
     * @return The runs, without their entries
     */
    @Operation(summary = "List recent backtest runs",
            description = "Returns the most recent runs, newest first. Stored entries are omitted from the list.")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Runs returned",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "limit is out of range",
                    content = @Content
            )
    })
    @GetMapping
    public ResponseEntity<List<BacktestRun>> recent(
            @Parameter(description = "Maximum number of runs to return (1-100)")
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_RECENT_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_RECENT_LIMIT);
        }
        return ResponseEntity.ok(backtestService.recent(limit));
    }
}
