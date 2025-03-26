package nl.jimkaplan.autotrader.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a new bot configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotConfigurationRequest {
    private String apiKey;
    private String apiSecret;
    private String tradingPair;
}