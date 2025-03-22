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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
       BitvavoAuthHeaders mockHeaders = BitvavoAuthHeaders.builder()
               .bitvavoBitvAvoAccessKey("mockKey")
               .bitvavoBitvAvoAccessSignature("mockSignature")
               .bitvavoBitvAvoAccessTimestamp("mockTimestamp")
               .bitvavoBitvAvoAccessWindow("mockWindow")
               .build();
       when(bitvavoConfig.getApiUrl()).thenReturn("https://api.bitvavo.com/v2");
       when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null)))
               .thenReturn(mockHeaders);
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
       verify(restTemplate).exchange(
               eq("https://api.bitvavo.com/v2" + endpoint),
               eq(HttpMethod.GET),
               any(),
               eq(Object.class)
       );
   }

    @Test
    void testPostRequest() {
        // This test verifies that the authentication service is called with the correct parameters
        // and that the API client correctly constructs the request

        // Arrange
        String endpoint = "/order";
        String body = "{\"market\":\"BTC-EUR\",\"side\":\"buy\",\"amount\":\"0.1\",\"orderId\":\"" + getRandomUUID() + "\"}";
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
        verify(restTemplate).exchange(
                eq("https://api.bitvavo.com/v2" + endpoint),
                eq(HttpMethod.POST),
                any(),
                eq(Object.class)
        );
    }

    @Test
    void testPostRequestWithInvalidBody() {
        // This test verifies that an IllegalArgumentException is thrown when the request body is invalid

        // Arrange
        String endpoint = "/order";
        Object invalidBody = new Object(); // This will cause JsonProcessingException

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> bitvavoApiClient.post(endpoint, invalidBody, Object.class));
    }

    private static UUID getRandomUUID() {
        return UUID.randomUUID();
    }
}
