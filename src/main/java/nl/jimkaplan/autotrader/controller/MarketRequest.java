package nl.jimkaplan.autotrader.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * The request that adds a pair to the catalogue.
 */
@Data
public class MarketRequest {

    /**
     * The market as typed, e.g. {@code ETH-EUR} or {@code ETHEUR}; the service normalises it.
     */
    @Schema(description = "Market in Bitvavo format", example = "ETH-EUR")
    private String market;
}
