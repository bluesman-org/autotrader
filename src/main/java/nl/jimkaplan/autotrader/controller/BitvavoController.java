package nl.jimkaplan.autotrader.controller;

import lombok.RequiredArgsConstructor;
import nl.jimkaplan.autotrader.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.model.CreateOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for Bitvavo API endpoints.
 */
@RestController
@RequestMapping("/api/bitvavo")
@RequiredArgsConstructor
public class BitvavoController {

    // TODO Cover exception handling with tests
    private static final Logger log = LoggerFactory.getLogger(BitvavoController.class);

    private final BitvavoApiClient bitvavoApiClient;

    /**
     * Get account information from Bitvavo API.
     *
     * @return Account information
     */
    @GetMapping("/account")
    public Map<String, Object> getAccount() {
        log.debug("Received request to get account information");
        Map<String, Object> result = bitvavoApiClient.get("/account", Map.class);
        log.debug("Successfully retrieved account information");
        return result;
    }

    /**
     * Get market information from Bitvavo API.
     *
     * @return Market information
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
}
