package nl.jimkaplan.autotrader.client;

import nl.jimkaplan.autotrader.config.BitvavoConfig;
import nl.jimkaplan.autotrader.model.BitvavoAuthHeaders;
import nl.jimkaplan.autotrader.service.BitvavoAuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BitvavoApiClientTest {

    @Mock
    private BitvavoConfig bitvavoConfig;

    @Mock
    private BitvavoAuthenticationService authenticationService;

    @Mock
    private RestTemplate restTemplate;

    private BitvavoApiClient bitvavoApiClient;

    @BeforeEach
    void setUp() {
        bitvavoApiClient = new BitvavoApiClient(restTemplate, bitvavoConfig, authenticationService);
    }

    @Test
    void testGetRequest() {
        // This test verifies that the authentication service is called with the correct parameters
        // and that the API client correctly constructs the request

        // Arrange
        String endpoint = "/account";
        when(bitvavoConfig.getApiUrl()).thenReturn("https://api.bitvavo.com/v2");
        when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null)))
                .thenReturn(BitvavoAuthHeaders.builder().build());
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(),
                eq(Object.class)
        )).thenReturn(ResponseEntity.ok().build());

        // Act
        bitvavoApiClient.get(endpoint, Object.class);

        // Assert
        verify(authenticationService).createAuthHeaders(eq("GET"), eq(endpoint), eq(null));
    }

    @Test
    void testPostRequest() {
        // This test verifies that the authentication service is called with the correct parameters
        // and that the API client correctly constructs the request

        // Arrange
        String endpoint = "/order";
        String body = "{\"market\":\"BTC-EUR\",\"side\":\"buy\",\"amount\":\"0.1\"}";
        when(bitvavoConfig.getApiUrl()).thenReturn("https://api.bitvavo.com/v2");
        when(authenticationService.createAuthHeaders(eq("POST"), eq(endpoint), eq(body)))
                .thenReturn(BitvavoAuthHeaders.builder().build());
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(),
                eq(Object.class)
        )).thenReturn(ResponseEntity.ok().build());

        // Act
        bitvavoApiClient.post(endpoint, body, Object.class);

        // Assert
        verify(authenticationService).createAuthHeaders(eq("POST"), eq(endpoint), eq(body));
    }
}
