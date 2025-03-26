package nl.jimkaplan.autotrader.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for returning bot configuration data for a newly created bot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotCreatedResponse {
    private String botId;
    private String tradingPair;
    private Boolean active;
    private String webhookApiKey;
}
