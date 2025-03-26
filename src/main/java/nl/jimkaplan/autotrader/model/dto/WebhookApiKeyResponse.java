package nl.jimkaplan.autotrader.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for returning a generated webhook API key.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebhookApiKeyResponse {
    private String apiKey;
}