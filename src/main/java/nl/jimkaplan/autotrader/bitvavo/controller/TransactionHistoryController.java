package nl.jimkaplan.autotrader.bitvavo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountHistoryResponse;
import nl.jimkaplan.autotrader.bitvavo.service.TransactionHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller for viewing Bitvavo transaction history for a bot.
 * Provides an endpoint to retrieve the transaction history of the account a bot trades on.
 */
@Slf4j
@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
@Tag(name = "Transaction History", description = "API for viewing Bitvavo transaction history")
public class TransactionHistoryController {

    private static final Set<String> ALLOWED_TRANSACTION_TYPES = Set.of(
            "buy", "sell", "staking", "fixed_staking", "deposit", "withdrawal",
            "affiliate", "distribution", "internal_transfer", "withdrawal_cancelled",
            "rebate", "loan", "external_transferred_funds", "manually_assigned");

    private static final String ALLOWED_TRANSACTION_TYPES_MESSAGE = ALLOWED_TRANSACTION_TYPES.stream()
            .sorted()
            .collect(Collectors.joining(", "));

    private final TransactionHistoryService transactionHistoryService;

    /**
     * Get the transaction history for a bot.
     * The history is account-wide; the bot only provides the API credentials used for the request.
     *
     * @param botId    The bot whose credentials are used
     * @param fromDate Optional Unix timestamp in milliseconds to return transactions from
     * @param toDate   Optional Unix timestamp in milliseconds to return transactions up to
     * @param page     Optional page number to return
     * @param maxItems Optional maximum number of items per page
     * @param type     Optional transaction type to filter by
     * @return The transaction history returned by Bitvavo
     */
    @Operation(
            summary = "Get transaction history for a bot",
            description = "Returns the Bitvavo transaction history for the account of the specified bot. " +
                          "The history is account-wide; the bot only provides the API credentials used for the request. " +
                          "Results can be filtered by date range and transaction type, and are paginated."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Transaction history retrieved successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = GetAccountHistoryResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid bot ID or request parameters",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Bot configuration not found within active configurations",
                    content = @Content
            )
    })
    @GetMapping("/{botId}/transactions")
    public ResponseEntity<GetAccountHistoryResponse> getTransactions(
            @Parameter(description = "ID of the bot whose credentials are used", required = true)
            @PathVariable String botId,

            @Parameter(description = "Unix timestamp in milliseconds to return transactions from")
            @RequestParam(required = false) Long fromDate,

            @Parameter(description = "Unix timestamp in milliseconds to return transactions up to")
            @RequestParam(required = false) Long toDate,

            @Parameter(description = "Page number to return")
            @RequestParam(required = false) Integer page,

            @Parameter(description = "Maximum number of items per page (1-100)")
            @RequestParam(required = false) Integer maxItems,

            @Parameter(description = "Transaction type to filter by (e.g. buy, sell, deposit, withdrawal)")
            @RequestParam(required = false) String type) {
        log.info("Received request to get transaction history for bot: {}", botId);

        validateBotId(botId);
        validateQueryParameters(fromDate, toDate, page, maxItems, type);

        GetAccountHistoryResponse response = transactionHistoryService
                .getTransactions(botId, fromDate, toDate, page, maxItems, type);

        log.info("Successfully retrieved transaction history for bot: {}", botId);
        return ResponseEntity.ok(response);
    }

    /**
     * Validate the format of the bot ID, consistent with the bot configuration endpoints.
     *
     * @param botId The bot ID to validate
     * @throws IllegalArgumentException if the bot ID format is invalid
     */
    private void validateBotId(String botId) {
        if (botId == null || botId.length() != 6 || !botId.matches("[a-zA-Z0-9]+")) {
            throw new IllegalArgumentException("Bot ID must be exactly 6 characters long and contain only letters and numbers");
        }
    }

    /**
     * Validate the optional query parameters against the ranges Bitvavo accepts.
     *
     * @throws IllegalArgumentException if a parameter is out of range
     */
    private void validateQueryParameters(Long fromDate, Long toDate, Integer page, Integer maxItems, String type) {
        if (fromDate != null && fromDate < 0) {
            throw new IllegalArgumentException("fromDate must not be negative");
        }
        if (toDate != null && toDate < 0) {
            throw new IllegalArgumentException("toDate must not be negative");
        }
        if (fromDate != null && toDate != null && fromDate > toDate) {
            throw new IllegalArgumentException("fromDate must not be after toDate");
        }
        if (page != null && page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (maxItems != null && (maxItems < 1 || maxItems > 100)) {
            throw new IllegalArgumentException("maxItems must be between 1 and 100");
        }
        if (type != null && !type.isBlank() && !ALLOWED_TRANSACTION_TYPES.contains(type)) {
            throw new IllegalArgumentException("type must be one of: " + ALLOWED_TRANSACTION_TYPES_MESSAGE);
        }
    }
}
