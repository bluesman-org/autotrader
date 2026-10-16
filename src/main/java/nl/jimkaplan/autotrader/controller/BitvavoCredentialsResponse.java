package nl.jimkaplan.autotrader.controller;

import java.time.Instant;

/**
 * What the API and the settings page read about the system-wide Bitvavo API key: whether one is
 * configured, a hint that identifies it and its operator id — never the key itself and never the
 * secret.
 *
 * @param configured Whether a key is stored
 * @param apiKeyHint A hint that identifies the key, e.g. {@code …f3a1}, or null when none is stored
 * @param operatorId The operator id Bitvavo expects on orders, or null
 * @param updatedTime When the key was stored or replaced, or null
 */
public record BitvavoCredentialsResponse(boolean configured, String apiKeyHint, Long operatorId,
                                         Instant updatedTime) {
}
