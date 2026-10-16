package nl.jimkaplan.autotrader.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.ErrorResponse;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The system-wide Bitvavo API key: what is configured (masked) and how to replace it. The secret is
 * write-only — no response ever carries it.
 */
@Slf4j
@RestController
@RequestMapping("/api/bitvavo-credentials")
@Tag(name = "Bitvavo credentials", description = "The system-wide Bitvavo API key the engine and the ledger use")
public class BitvavoCredentialsController {

    private final BitvavoCredentialsService credentialsService;

    public BitvavoCredentialsController(BitvavoCredentialsService credentialsService) {
        this.credentialsService = credentialsService;
    }

    /**
     * Read what is configured: whether a key is stored, a hint that identifies it and the operator
     * id. Neither the key nor the secret is returned.
     *
     * @return The masked view
     */
    @Operation(summary = "Read the system-wide Bitvavo API key (masked)",
            description = "Returns whether a key is configured, a hint that identifies it and the "
                    + "operator id. The key and the secret are never returned.")
    @GetMapping
    public BitvavoCredentialsResponse get() {
        return credentialsService.view();
    }

    /**
     * Store or replace the key.
     *
     * @param request The key, the secret and the operator id
     * @return The masked view of what is stored now
     */
    @Operation(summary = "Store or replace the system-wide Bitvavo API key",
            description = "Stores the key encrypted; it is used by the live engine and the ledger.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The key was stored"),
            @ApiResponse(responseCode = "400", description = "The key or the secret was missing",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping
    public BitvavoCredentialsResponse replace(@RequestBody BitvavoCredentialsRequest request) {
        credentialsService.save(request.getApiKey(), request.getApiSecret(), request.getOperatorId());
        return credentialsService.view();
    }
}
