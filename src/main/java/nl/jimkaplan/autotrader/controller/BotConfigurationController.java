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
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.model.dto.BotConfigurationRequest;
import nl.jimkaplan.autotrader.model.dto.BotConfigurationResponse;
import nl.jimkaplan.autotrader.model.dto.BotCreatedResponse;
import nl.jimkaplan.autotrader.model.dto.WebhookApiKeyResponse;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller for managing bot configurations.
 * Provides endpoints for CRUD operations on bot configurations.
 */
@Slf4j
@RestController
@RequestMapping("/api/bots")
@RequiredArgsConstructor
@Tag(name = "Bot Configuration", description = "API for managing trading bot configurations")
public class BotConfigurationController {

    private final BotConfigurationService botConfigurationService;

    /**
     * Create a new bot configuration.
     *
     * @param request The bot configuration request
     * @return The created bot configuration
     */
    @Operation(
            summary = "Create a new bot configuration",
            description = "Creates a new trading bot configuration with the provided details"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Bot configuration created successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BotConfigurationResponse.class)
                    )
            )
    })
    @PostMapping("/create")
    public ResponseEntity<BotCreatedResponse> createBotConfiguration(
            @Parameter(description = "Bot configuration details", required = true)
            @RequestBody BotConfigurationRequest request) {
        log.info("Received request to create bot configuration");

        BotConfiguration config = BotConfiguration.builder()
                .botId(botConfigurationService.generateBotId())
                .apiKey(request.getApiKey())
                .apiSecret(request.getApiSecret())
                .tradingPair(request.getTradingPair())
                .active(true)
                .build();

        BotConfiguration savedConfig = botConfigurationService.saveBotConfiguration(config);

        String webhookApiKey = botConfigurationService.generateAndSaveWebhookApiKey(savedConfig.getBotId());

        BotCreatedResponse response = BotCreatedResponse.builder()
                .botId(savedConfig.getBotId())
                .tradingPair(savedConfig.getTradingPair())
                .active(savedConfig.getActive())
                .webhookApiKey(webhookApiKey)
                .build();

        log.info("Successfully created bot configuration with ID: {}", response.getBotId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get a bot configuration by ID.
     *
     * @param botId The bot ID
     * @return The bot configuration
     */
    @Operation(
            summary = "Get a bot configuration by ID",
            description = "Retrieves the details of a specific bot configuration"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Bot configuration retrieved successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BotConfigurationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Bot configuration not found within active configurations",
                    content = @Content
            )
    })
    @GetMapping("/{botId}")
    public ResponseEntity<BotConfigurationResponse> getBotConfiguration(
            @Parameter(description = "ID of the bot configuration to retrieve", required = true)
            @PathVariable String botId) {
        log.info("Received request to get bot configuration with ID: {}", botId);

        return botConfigurationService.getBotConfiguration(botId)
                .map(config -> {
                    BotConfigurationResponse response = mapToResponse(config);
                    log.info("Successfully retrieved bot configuration with ID: {}", botId);
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    log.warn("Bot configuration with ID: {} not found within active configurations", botId);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Get all active bot configurations.
     *
     * @return List of all active bot configurations
     */
    @Operation(
            summary = "Get all bot configurations",
            description = "Retrieves a list of all bot configurations, optionally including inactive ones"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Bot configurations retrieved successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BotConfigurationResponse.class)
                    )
            )
    })
    @GetMapping
    public ResponseEntity<List<BotConfigurationResponse>> getAllBotConfigurations(
            @Parameter(description = "Whether to include inactive bot configurations")
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        log.info("Received request to get all bot configurations, includeInactive: {}", includeInactive);

        List<BotConfiguration> configs = includeInactive ?
                botConfigurationService.getAllBotConfigurationsIncludingInactive() :
                botConfigurationService.getAllBotConfigurations();

        List<BotConfigurationResponse> response = configs.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        log.info("Successfully retrieved {} bot configurations", response.size());

        // If no configurations are found, return a message in the response header
        if (response.isEmpty()) {
            log.warn("No bot configurations found");
            return ResponseEntity.status(HttpStatus.NO_CONTENT)
                    .header("X-Message", "No bot configurations found")
                    .body(response);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Deactivate a bot configuration.
     *
     * @param botId The bot ID
     * @return No content if successful, not found if the bot doesn't exist
     */
    @Operation(
            summary = "Deactivate a bot configuration",
            description = "Deactivates a specific bot configuration so it no longer processes trades"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Bot configuration deactivated successfully",
                    content = @Content(
                            mediaType = "text/plain",
                            schema = @Schema(type = "string")
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Bot configuration not found within active configurations",
                    content = @Content
            )
    })
    @PatchMapping("/deactivate/{botId}")
    public ResponseEntity<String> deactivateBotConfiguration(
            @Parameter(description = "ID of the bot configuration to deactivate", required = true)
            @PathVariable String botId) {
        log.info("Received request to deactivate bot configuration with ID: {}", botId);

        boolean deactivated = botConfigurationService.deactivateBotConfiguration(botId);

        if (deactivated) {
            log.info("Successfully deactivated bot configuration with ID: {}", botId);
            return ResponseEntity.ok("Bot configuration deactivated ID: " + botId);
        } else {
            log.warn("Bot configuration with ID: {} not found within active configurations", botId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Activate a bot configuration.
     *
     * @param botId The bot ID
     * @return No content if successful, not found if the bot doesn't exist
     */
    @Operation(
            summary = "Activate a bot configuration",
            description = "Activates a specific bot configuration so it can process trades"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Bot configuration activated successfully",
                    content = @Content(
                            mediaType = "text/plain",
                            schema = @Schema(type = "string")
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Bot configuration not found within active configurations",
                    content = @Content
            )
    })
    @PatchMapping("/activate/{botId}")
    public ResponseEntity<String> activateBotConfiguration(
            @Parameter(description = "ID of the bot configuration to activate", required = true)
            @PathVariable String botId) {
        log.info("Received request to activate bot configuration with ID: {}", botId);

        boolean activated = botConfigurationService.activateBotConfiguration(botId);

        if (activated) {
            log.info("Successfully activated bot configuration with ID: {}", botId);
            return ResponseEntity.ok("Bot configuration activated ID: " + botId);
        } else {
            log.warn("Bot configuration with ID: {} not found within active configurations", botId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Generate a new webhook API key for a bot.
     *
     * @param botId The bot ID
     * @return The generated webhook API key
     */
    @Operation(
            summary = "Generate a new webhook API key",
            description = "Generates a new webhook API key for a specific bot configuration"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Webhook API key generated successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = WebhookApiKeyResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Bot configuration not found within active configurations",
                    content = @Content
            )
    })
    @PostMapping("/webhook-key/{botId}")
    public ResponseEntity<WebhookApiKeyResponse> generateWebhookApiKey(
            @Parameter(description = "ID of the bot configuration to generate a webhook API key for", required = true)
            @PathVariable String botId) {
        log.info("Received request to generate webhook API key for bot with ID: {}", botId);

        // Check if the bot exists
        if (botConfigurationService.getBotConfigurationIncludingInactive(botId).isEmpty()) {
            log.warn("Bot configuration with ID: {} not found within active configurations", botId);
            return ResponseEntity.notFound().build();
        }

        String apiKey = botConfigurationService.generateAndSaveWebhookApiKey(botId);
        WebhookApiKeyResponse response = new WebhookApiKeyResponse(apiKey);

        log.info("Successfully generated webhook API key for bot with ID: {}", botId);
        return ResponseEntity.ok(response);
    }

    /**
     * Map a BotConfiguration entity to a BotConfigurationResponse DTO.
     *
     * @param config The BotConfiguration entity
     * @return The BotConfigurationResponse DTO
     */
    private BotConfigurationResponse mapToResponse(BotConfiguration config) {
        return BotConfigurationResponse.builder()
                .botId(config.getBotId())
                .tradingPair(config.getTradingPair())
                .active(config.getActive())
                .build();
    }
}
