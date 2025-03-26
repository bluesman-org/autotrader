package nl.jimkaplan.autotrader.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for returning bot configuration data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotConfigurationResponse {
    private String botId;
    private String tradingPair;
    private Boolean active;
}