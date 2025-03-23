package nl.jimkaplan.autotrader.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import nl.jimkaplan.autotrader.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.model.GetAccountFeesResponse;
import nl.jimkaplan.autotrader.model.GetAssetDataResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller for Bitvavo API endpoints.
 */
@RestController
@RequestMapping("/api/bitvavo")
@RequiredArgsConstructor
public class BitvavoController {

    private static final Logger log = LoggerFactory.getLogger(BitvavoController.class);

    private final BitvavoApiClient bitvavoApiClient;

    /**
     * Returns the current fees for account from Bitvavo API.
     *
     * @return Account fees information
     */
    @GetMapping("/account")
    public GetAccountFeesResponse getAccount() {
        log.debug("Received request to get account information");
        GetAccountFeesResponse result = bitvavoApiClient.get("/account", GetAccountFeesResponse.class);
        log.debug("Successfully retrieved account information");
        return result;
    }

    /**
     * Returns the current Unix timestamp of Bitvavo servers.
     *
     * @return timestamp
     */
    @GetMapping("/time")
    public Object getServerTime() {
        log.debug("Received request to get server time");
        Object result = bitvavoApiClient.get("/time", Object.class);
        log.debug("Successfully retrieved server time");
        return result;
    }

    /**
     * Create a new order on Bitvavo.
     *
     * @param request The order request
     * @return The created order
     */
    @PostMapping("/order")
    public CreateOrderResponse createOrder(@RequestBody CreateOrderRequest request) {
        log.debug("Received request to create order: {}", request);
        CreateOrderResponse result = bitvavoApiClient.post("/order", request, CreateOrderResponse.class);
        log.debug("Successfully created order with ID: {}", result.getOrderId());
        return result;
    }

    /**
     * Get asset data from Bitvavo API.
     *
     * @param symbol The symbol of the asset to retrieve. Example: BTC, EUR (case-sensitive).
     *               Leave empty to retrieve all assets.
     * @return Asset data
     */
    @GetMapping("/assets")
    public List<GetAssetDataResponse> getAssetData(@RequestParam(name = "symbol", required = false) String symbol) {
        String symbolLog = (symbol == null || symbol.isEmpty()) ? "all" : symbol;
        log.debug("Received request to get asset data for symbol: {}", symbolLog);

        String endpoint = (symbol == null || symbol.isEmpty()) ? "/assets" : "/assets?symbol=" + symbol;
        Object response = bitvavoApiClient.get(endpoint, Object.class);
        ObjectMapper mapper = new ObjectMapper();
        List<GetAssetDataResponse> assetData;

        if (response instanceof List) {
            assetData = ((List<?>) response).stream()
                    .map(item -> mapper.convertValue(item, GetAssetDataResponse.class))
                    .collect(Collectors.toList());
        } else {
            assetData = Collections.singletonList(mapper.convertValue(response, GetAssetDataResponse.class));
        }
        log.debug("Successfully retrieved asset data for {} asset(s)", assetData.size());
        return assetData;
    }
}
