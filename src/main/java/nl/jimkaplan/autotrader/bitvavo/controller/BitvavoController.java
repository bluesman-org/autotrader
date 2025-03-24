package nl.jimkaplan.autotrader.bitvavo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountBalanceResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountFeesResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAssetDataResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetPriceResponse;
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
        return getData(symbol, "/assets", GetAssetDataResponse.class);
    }

    /**
     * Get balance data from Bitvavo API.
     *
     * @param symbol The symbol of the asset to retrieve. Example: BTC, EUR (case-sensitive).
     *               Leave empty to retrieve the balance of all assets above zero.
     * @return Balance data
     */
    @GetMapping("/balance")
    public List<GetAccountBalanceResponse> getBalance(@RequestParam(name = "symbol", required = false) String symbol) {
        return getData(symbol, "/balance", GetAccountBalanceResponse.class);
    }

    /**
     * Returns the latest trades on Bitvavo for all markets or a single market.
     *
     * @param market The market for which you want to retrieve the latest trades.
     *               Example: BTC-EUR
     * @return The latest trades
     */
    @GetMapping("/ticker/price")
    public List<GetPriceResponse> getPrice(@RequestParam(name = "market", required = false) String market) {
        return getData(market, "/ticker/price", GetPriceResponse.class);
    }

    private <T> List<T> getData(final String symbol, final String endpointBase, final Class<T> responseType) {
        boolean isSymbolPresent = symbol != null && !symbol.isEmpty();

        String symbolLog = isSymbolPresent ? symbol : "all";
        log.debug("Received request to get data for symbol: {}", symbolLog);

        String endpoint = endpointBase;
        if (isSymbolPresent) {
            // Check if responseType is GetPriceResponse, then use the market parameter
            if (responseType == GetPriceResponse.class) {
                endpoint += "?market=" + symbol;
            } else {
                endpoint += "?symbol=" + symbol;
            }
        }

        Object response = bitvavoApiClient.get(endpoint, Object.class);
        ObjectMapper mapper = new ObjectMapper();
        List<T> data;

        if (response instanceof List) {
            data = ((List<?>) response).stream()
                    .map(item -> mapper.convertValue(item, responseType))
                    .collect(Collectors.toList());
        } else {
            data = Collections.singletonList(mapper.convertValue(response, responseType));
        }
        log.debug("Successfully retrieved data for {} item(s)", data.size());
        return data;
    }
}
