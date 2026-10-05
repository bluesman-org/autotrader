package nl.jimkaplan.autotrader.bitvavo.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jimkaplan.autotrader.bitvavo.model.BitvavoAuthHeaders;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.bitvavo.service.BitvavoAuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BitvavoApiClientTest {

    @Mock
    private BitvavoAuthenticationService authenticationService;

    @Mock
    private RestTemplate restTemplate;

    private BitvavoApiClient bitvavoApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey = "test-api-key";
    private final String apiSecret = "test-api-secret";
    private final String apiUrl = "https://api.bitvavo.com/v2";

    @BeforeEach
    void setUp() {
        bitvavoApiClient = new BitvavoApiClient(restTemplate, authenticationService, objectMapper);
        ReflectionTestUtils.setField(bitvavoApiClient, "apiUrl", apiUrl);
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
        when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null), eq(apiKey), eq(apiSecret)))
                .thenReturn(mockHeaders);
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(),
                eq(Object.class)
        )).thenReturn(ResponseEntity.ok().build());

        // Act
        bitvavoApiClient.get(endpoint, Object.class, apiKey, apiSecret);

        // Assert
        verify(authenticationService).createAuthHeaders(eq("GET"), eq(endpoint), eq(null), eq(apiKey), eq(apiSecret));
        verify(restTemplate).exchange(
                eq(apiUrl + endpoint),
                eq(HttpMethod.GET),
                any(),
                eq(Object.class)
        );
    }

    @Test
    void testPostRequest() throws Exception {
        // This test verifies that the authentication service is called with the correct parameters
        // and that the API client correctly constructs and serializes the request

        // Arrange
        String endpoint = "/order";
        CreateOrderRequest orderRequest = CreateOrderRequest.builder()
                .market("BTC-EUR")
                .side("buy")
                .orderType("market")
                .amountQuote(new BigDecimal("100.45"))
                .operatorId(543462L)
                .build();
        when(authenticationService.createAuthHeaders(eq("POST"), eq(endpoint), any(), eq(apiKey), eq(apiSecret)))
                .thenReturn(BitvavoAuthHeaders.builder().build());
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(),
                eq(Object.class)
        )).thenReturn(ResponseEntity.ok().build());

        // Act
        bitvavoApiClient.post(endpoint, orderRequest, Object.class, apiKey, apiSecret);

        // Assert: the body string used for signing must be the exact body that is sent
        ArgumentCaptor<String> signedBodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(authenticationService).createAuthHeaders(eq("POST"), eq(endpoint), signedBodyCaptor.capture(), eq(apiKey), eq(apiSecret));
        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(apiUrl + endpoint),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(Object.class)
        );
        HttpEntity<?> sentEntity = entityCaptor.getValue();
        assertEquals(signedBodyCaptor.getValue(), sentEntity.getBody());
        assertEquals(MediaType.APPLICATION_JSON, sentEntity.getHeaders().getContentType());
        // The signed body and the sent body must represent the same JSON payload;
        // amounts are serialized as strings, as the Bitvavo API expects
        assertEquals(
                objectMapper.readTree("{\"market\":\"BTC-EUR\",\"side\":\"buy\",\"orderType\":\"market\","
                        + "\"amountQuote\":\"100.45\",\"operatorId\":543462}"),
                objectMapper.readTree(sentEntity.getBody().toString()));
    }

    @Test
    void testPostRequestWithInvalidBody() {
        // This test verifies that an IllegalArgumentException is thrown when the request body cannot be serialized

        // Arrange
        String endpoint = "/order";
        Map<String, Object> invalidBody = new HashMap<>();
        invalidBody.put("self", invalidBody); // Circular reference, causes JsonProcessingException

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> bitvavoApiClient.post(endpoint, invalidBody, Object.class, apiKey, apiSecret));
    }

    @Test
    void testGetRequest_withHttpClientErrorException_propagatesException() {
        // Arrange
        String endpoint = "/account";
        BitvavoAuthHeaders mockHeaders = BitvavoAuthHeaders.builder().build();
        when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null), eq(apiKey), eq(apiSecret)))
                .thenReturn(mockHeaders);
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(),
                eq(Object.class)
        )).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));

        // Act & Assert
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                () -> bitvavoApiClient.get(endpoint, Object.class, apiKey, apiSecret));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("400 Bad Request", exception.getMessage());
    }

    @Test
    void testGetRequest_withHttpServerErrorException_propagatesException() {
        // Arrange
        String endpoint = "/account";
        BitvavoAuthHeaders mockHeaders = BitvavoAuthHeaders.builder().build();
        when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null), eq(apiKey), eq(apiSecret)))
                .thenReturn(mockHeaders);
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(),
                eq(Object.class)
        )).thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"));

        // Act & Assert
        HttpServerErrorException exception = assertThrows(HttpServerErrorException.class,
                () -> bitvavoApiClient.get(endpoint, Object.class, apiKey, apiSecret));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
        assertEquals("500 Internal Server Error", exception.getMessage());
    }

    @Test
    void testGetRequest_withResourceAccessException_propagatesException() {
        // Arrange
        String endpoint = "/account";
        BitvavoAuthHeaders mockHeaders = BitvavoAuthHeaders.builder().build();
        when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null), eq(apiKey), eq(apiSecret)))
                .thenReturn(mockHeaders);
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(),
                eq(Object.class)
        )).thenThrow(new ResourceAccessException("Connection refused", new ConnectException("Connection refused")));

        // Act & Assert
        ResourceAccessException exception = assertThrows(ResourceAccessException.class,
                () -> bitvavoApiClient.get(endpoint, Object.class, apiKey, apiSecret));
        assertEquals("Connection refused", exception.getMessage());
    }

    @Test
    void testGetRequest_withRestClientException_propagatesException() {
        // Arrange
        String endpoint = "/account";
        BitvavoAuthHeaders mockHeaders = BitvavoAuthHeaders.builder().build();
        when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null), eq(apiKey), eq(apiSecret)))
                .thenReturn(mockHeaders);
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(),
                eq(Object.class)
        )).thenThrow(new RestClientException("Unknown error"));

        // Act & Assert
        RestClientException exception = assertThrows(RestClientException.class,
                () -> bitvavoApiClient.get(endpoint, Object.class, apiKey, apiSecret));
        assertEquals("Unknown error", exception.getMessage());
    }

    @Test
    void testPostRequest_withHttpClientErrorException_propagatesException() {
        // Arrange
        String endpoint = "/order";
        CreateOrderRequest orderRequest = CreateOrderRequest.builder()
                .market("BTC-EUR")
                .side("buy")
                .orderType("market")
                .amount(new BigDecimal("0.1"))
                .operatorId(543462L)
                .build();
        when(authenticationService.createAuthHeaders(eq("POST"), eq(endpoint), any(), eq(apiKey), eq(apiSecret)))
                .thenReturn(BitvavoAuthHeaders.builder().build());
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(),
                eq(Object.class)
        )).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));

        // Act & Assert
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                () -> bitvavoApiClient.post(endpoint, orderRequest, Object.class, apiKey, apiSecret));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("400 Bad Request", exception.getMessage());
    }
}