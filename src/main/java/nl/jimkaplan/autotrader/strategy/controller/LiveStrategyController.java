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
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Controller for creating, running and inspecting live strategies.
 */
@Slf4j
@RestController
@RequestMapping("/api/live-strategies")
@RequiredArgsConstructor
@Tag(name = "Live strategies", description = "API for running strategies live on closed candles")
public class LiveStrategyController {

    private final LiveStrategyService liveStrategyService;

    /**
     * Create a strategy; it is not running until started.
     *
     * @param request The strategy; market and interval are required, dry-run defaults to true
     * @return The stored strategy
     */
    @Operation(summary = "Create a live strategy",
            description = "Creates a strategy from the same rules, parameters and sizing a backtest " +
                          "uses. Dry-run defaults to true; live orders need the system-wide Bitvavo " +
                          "API key, including its operator ID, stored on the settings page.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Strategy created",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LiveStrategy.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content)
    })
    @PostMapping
    public ResponseEntity<LiveStrategy> create(@RequestBody LiveStrategyRequest request) {
        LiveStrategy strategy = liveStrategyService.create(request);
        log.info("Created live strategy {} ({} {}, dry-run={})", strategy.getId(), strategy.getMarket(),
                strategy.getInterval(), strategy.isDryRun());
        return ResponseEntity.status(HttpStatus.CREATED).body(strategy);
    }

    /**
     * List all live strategies.
     *
     * @return The strategies
     */
    @Operation(summary = "List live strategies")
    @ApiResponse(responseCode = "200", description = "Strategies returned",
            content = @Content(mediaType = "application/json"))
    @GetMapping
    public ResponseEntity<List<LiveStrategy>> all() {
        return ResponseEntity.ok(liveStrategyService.all());
    }

    /**
     * One strategy with its runtime state.
     *
     * @param id The strategy ID
     * @return The strategy, running state and stream state
     */
    @Operation(summary = "Get a live strategy", operationId = "getLiveStrategy")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Strategy found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LiveStrategyView.class))),
            @ApiResponse(responseCode = "404", description = "No strategy with that ID", content = @Content)
    })
    @GetMapping("/{id}")
    public ResponseEntity<LiveStrategyView> get(
            @Parameter(description = "ID of the live strategy", required = true) @PathVariable String id) {
        return ResponseEntity.ok(view(id));
    }

    /**
     * Start a strategy: seed its history, connect the candle stream and evaluate closed candles.
     *
     * @param id The strategy ID
     * @return The strategy's runtime state
     */
    @Operation(summary = "Start a live strategy",
            description = "Seeds the strategy's candle history, subscribes to its candle stream and " +
                          "evaluates every candle that closes while it runs. Old candles are never " +
                          "replayed.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Strategy started",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LiveStrategyView.class))),
            @ApiResponse(responseCode = "404", description = "No strategy with that ID", content = @Content)
    })
    @PostMapping("/{id}/start")
    public ResponseEntity<LiveStrategyView> start(
            @Parameter(description = "ID of the live strategy", required = true) @PathVariable String id) {
        liveStrategyService.start(id);
        return ResponseEntity.ok(view(id));
    }

    /**
     * Stop a strategy and clear its enabled flag.
     *
     * @param id The strategy ID
     * @return The strategy's runtime state
     */
    @Operation(summary = "Stop a live strategy")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Strategy stopped",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LiveStrategyView.class))),
            @ApiResponse(responseCode = "404", description = "No strategy with that ID", content = @Content)
    })
    @PostMapping("/{id}/stop")
    public ResponseEntity<LiveStrategyView> stop(
            @Parameter(description = "ID of the live strategy", required = true) @PathVariable String id) {
        liveStrategyService.stop(id);
        return ResponseEntity.ok(view(id));
    }

    /**
     * A strategy's orders, newest first: dry-run fills, real fills and failures.
     *
     * @param id The strategy ID
     * @return The orders
     */
    @Operation(summary = "List a strategy's orders",
            description = "Dry-run fills, live fills and failures, newest first.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Orders returned",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "404", description = "No strategy with that ID", content = @Content)
    })
    @GetMapping("/{id}/orders")
    public ResponseEntity<List<LiveOrder>> orders(
            @Parameter(description = "ID of the live strategy", required = true) @PathVariable String id) {
        return ResponseEntity.ok(liveStrategyService.orders(id));
    }

    private LiveStrategyView view(String id) {
        LiveStrategy strategy = liveStrategyService.get(id)
                .orElseThrow(() -> new NoSuchElementException("Live strategy not found: " + id));
        return new LiveStrategyView(strategy, liveStrategyService.isRunning(id),
                liveStrategyService.isConnected(id), liveStrategyService.lastError(id));
    }
}
