package nl.jimkaplan.autotrader.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.Market;
import nl.jimkaplan.autotrader.service.MarketService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The trading-pair catalogue: what the strategy forms and the account ledger offer.
 *
 * <p>The pairs are discovered from the account's own history, not from Bitvavo's full offering,
 * and a pair is disabled rather than deleted. The API exposes the same operations the settings
 * page does, so a script can manage the list without a browser.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/markets")
@RequiredArgsConstructor
@Tag(name = "Markets", description = "The trading-pair catalogue the forms and the ledger read")
public class MarketController {

    private final MarketService marketService;

    /**
     * List every pair in the catalogue.
     *
     * @return The pairs, enabled first and then by most recent trade
     */
    @Operation(summary = "List the trading pairs", operationId = "listMarkets")
    @ApiResponse(responseCode = "200", description = "Pairs returned",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = Market.class)))
    @GetMapping
    public ResponseEntity<List<Market>> all() {
        return ResponseEntity.ok(marketService.all());
    }

    /**
     * Add a pair by hand, for an asset that arrived without a buy.
     *
     * @param request The market symbol
     * @return The stored pair
     */
    @Operation(summary = "Add a trading pair", operationId = "addMarket",
            description = "Validates the pair against Bitvavo's public market list first: it must " +
                          "exist, be EUR-quoted and be trading.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Pair added",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = Market.class))),
            @ApiResponse(responseCode = "400", description = "Invalid, unknown or duplicate pair",
                    content = @Content)
    })
    @PostMapping
    public ResponseEntity<Market> add(@RequestBody MarketRequest request) {
        Market market = marketService.add(request.getMarket());
        log.info("Added trading pair {}", market.getMarket());
        return ResponseEntity.status(HttpStatus.CREATED).body(market);
    }

    /**
     * Show a pair in the forms and the ledger.
     *
     * @param market The market symbol
     * @return The updated pair
     */
    @Operation(summary = "Enable a trading pair", operationId = "enableMarket")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Pair enabled",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = Market.class))),
            @ApiResponse(responseCode = "404", description = "No such pair", content = @Content)
    })
    @PostMapping("/{market}/enable")
    public ResponseEntity<Market> enable(
            @Parameter(description = "Market in Bitvavo format", required = true)
            @PathVariable String market) {
        return ResponseEntity.ok(marketService.setEnabled(market, true));
    }

    /**
     * Hide a pair from the forms and the ledger without forgetting its history.
     *
     * @param market The market symbol
     * @return The updated pair
     */
    @Operation(summary = "Disable a trading pair", operationId = "disableMarket")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Pair disabled",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = Market.class))),
            @ApiResponse(responseCode = "404", description = "No such pair", content = @Content)
    })
    @PostMapping("/{market}/disable")
    public ResponseEntity<Market> disable(
            @Parameter(description = "Market in Bitvavo format", required = true)
            @PathVariable String market) {
        return ResponseEntity.ok(marketService.setEnabled(market, false));
    }

    /**
     * Read the account's trade history and add or refresh the pairs behind it.
     *
     * @return What the read found and changed
     */
    @Operation(summary = "Discover the account's traded pairs", operationId = "discoverMarkets",
            description = "Reads the account history with the system-wide key and adds or " +
                          "refreshes every EUR pair the account traded. Disabled pairs stay " +
                          "disabled; nothing is deleted.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Discovery completed",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = MarketService.Discovery.class))),
            @ApiResponse(responseCode = "400", description = "No Bitvavo API key is configured",
                    content = @Content)
    })
    @PostMapping("/discover")
    public ResponseEntity<MarketService.Discovery> discover() {
        MarketService.Discovery discovery = marketService.discover();
        log.info("Discovered {} traded markets ({} added)", discovery.markets(), discovery.added());
        return ResponseEntity.ok(discovery);
    }
}
