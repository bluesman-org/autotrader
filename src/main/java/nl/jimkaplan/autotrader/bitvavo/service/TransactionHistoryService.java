package nl.jimkaplan.autotrader.bitvavo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountHistoryResponse;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.NoSuchElementException;

/**
 * Service for retrieving the Bitvavo transaction history for a bot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionHistoryService {

    private static final String ACCOUNT_HISTORY_ENDPOINT = "/account/history";

    private final BotConfigurationService botConfigurationService;
    private final BitvavoApiClient bitvavoApiClient;

    /**
     * Get the transaction history for the account of a bot.
     * The history is account-wide; the bot only provides the API credentials used for the request.
     *
     * @param botId    The bot whose credentials are used
     * @param fromDate Optional Unix timestamp in milliseconds to return transactions from
     * @param toDate   Optional Unix timestamp in milliseconds to return transactions up to
     * @param page     Optional page number to return
     * @param maxItems Optional maximum number of items per page
     * @param type     Optional transaction type to filter by
     * @return The transaction history returned by Bitvavo
     * @throws NoSuchElementException if the bot configuration is not found
     */
    public GetAccountHistoryResponse getTransactions(String botId, Long fromDate, Long toDate,
                                                     Integer page, Integer maxItems, String type) {
        BotConfiguration botConfig = botConfigurationService.getBotConfiguration(botId)
                .orElseThrow(() -> new NoSuchElementException("Bot configuration not found: " + botId));

        String endpoint = buildAccountHistoryEndpoint(fromDate, toDate, page, maxItems, type);
        log.info("Fetching Bitvavo transaction history for bot: {}", botId);

        return bitvavoApiClient.get(endpoint, GetAccountHistoryResponse.class,
                botConfig.getApiKey(), botConfig.getApiSecret());
    }

    /**
     * Build the account history endpoint including only the provided query parameters.
     *
     * @return The endpoint with the query string in Bitvavo's format
     */
    private String buildAccountHistoryEndpoint(Long fromDate, Long toDate, Integer page,
                                               Integer maxItems, String type) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(ACCOUNT_HISTORY_ENDPOINT);
        if (fromDate != null) {
            builder.queryParam("fromDate", fromDate);
        }
        if (toDate != null) {
            builder.queryParam("toDate", toDate);
        }
        if (page != null) {
            builder.queryParam("page", page);
        }
        if (maxItems != null) {
            builder.queryParam("maxItems", maxItems);
        }
        if (type != null && !type.isEmpty()) {
            builder.queryParam("type", type);
        }
        return builder.build().encode().toUriString();
    }
}
