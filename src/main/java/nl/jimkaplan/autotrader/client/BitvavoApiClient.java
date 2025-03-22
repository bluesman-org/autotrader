package nl.jimkaplan.autotrader.client;

import lombok.RequiredArgsConstructor;
import nl.jimkaplan.autotrader.config.BitvavoConfig;
import nl.jimkaplan.autotrader.model.BitvavoAuthHeaders;
import nl.jimkaplan.autotrader.service.BitvavoAuthenticationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client for interacting with the Bitvavo API.
 */
@Component
@RequiredArgsConstructor
public class BitvavoApiClient {

    private static final Logger log = LoggerFactory.getLogger(BitvavoApiClient.class);

    private final RestTemplate restTemplate;
    private final BitvavoConfig bitvavoConfig;
    private final BitvavoAuthenticationService authenticationService;

    /**
     * Sends a GET request to the Bitvavo API.
     *
     * @param endpoint API endpoint (e.g., "/account")
     * @param responseType Class of the expected response
     * @return Response from the API
     */
    public <T> T get(String endpoint, Class<T> responseType) {
        log.debug("Sending GET request to Bitvavo API: {}", endpoint);
        try {
            HttpHeaders headers = createHeaders(HttpMethod.GET.name(), endpoint, null);
            HttpEntity<?> entity = new HttpEntity<>(headers);
            String url = bitvavoConfig.getApiUrl() + endpoint;

            log.debug("Making request to: {}", url);
            ResponseEntity<T> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    responseType
            );

            log.debug("Received response from Bitvavo API: {} with status {}", endpoint, response.getStatusCode());
            return response.getBody();
        } catch (Exception e) {
            log.error("Error during GET request to Bitvavo API: {}", endpoint, e);
            throw e;
        }
    }

    /**
     * Sends a POST request to the Bitvavo API.
     *
     * @param endpoint API endpoint (e.g., "/order")
     * @param body Request body
     * @param responseType Class of the expected response
     * @return Response from the API
     */
    public <T> T post(String endpoint, Object body, Class<T> responseType) {
        log.debug("Sending POST request to Bitvavo API: {}", endpoint);
        try {
            String bodyString = body != null ? body.toString() : "";
            log.debug("Request body: {}", bodyString);

            HttpHeaders headers = createHeaders(HttpMethod.POST.name(), endpoint, bodyString);
            HttpEntity<?> entity = new HttpEntity<>(body, headers);
            String url = bitvavoConfig.getApiUrl() + endpoint;

            log.debug("Making request to: {}", url);
            ResponseEntity<T> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    responseType
            );

            log.debug("Received response from Bitvavo API: {} with status {}", endpoint, response.getStatusCode());
            return response.getBody();
        } catch (Exception e) {
            log.error("Error during POST request to Bitvavo API: {}", endpoint, e);
            throw e;
        }
    }

    /**
     * Creates HTTP headers with Bitvavo authentication.
     *
     * @param method HTTP method (GET, POST, etc.)
     * @param endpoint API endpoint
     * @param body Request body (for POST requests)
     * @return HTTP headers with authentication
     */
    private HttpHeaders createHeaders(String method, String endpoint, String body) {
        log.debug("Creating authentication headers for {} request to {}", method, endpoint);
        try {
            BitvavoAuthHeaders authHeaders = authenticationService.createAuthHeaders(method, endpoint, body);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Bitvavo-Access-Key", authHeaders.getBitvavoBitvAvoAccessKey());
            headers.set("Bitvavo-Access-Signature", authHeaders.getBitvavoBitvAvoAccessSignature());
            headers.set("Bitvavo-Access-Timestamp", authHeaders.getBitvavoBitvAvoAccessTimestamp());
            headers.set("Bitvavo-Access-Window", authHeaders.getBitvavoBitvAvoAccessWindow());
            headers.set("Accept", "application/json");
            if (method.equals("GET"))
                headers.set("Content-Length", "0");

            log.debug("Authentication headers created successfully");
            return headers;
        } catch (IllegalStateException e) {
            log.error("Failed to create Bitvavo authentication headers", e);
            throw new RuntimeException("Failed to create Bitvavo authentication headers: " + e.getMessage(), e);
        }
    }
}
