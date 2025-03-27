//package nl.jimkaplan.autotrader.bitvavo.client;
//
//import nl.jimkaplan.autotrader.bitvavo.config.BitvavoConfig;
//import nl.jimkaplan.autotrader.bitvavo.model.BitvavoAuthHeaders;
//import nl.jimkaplan.autotrader.bitvavo.service.BitvavoAuthenticationService;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.http.HttpMethod;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.client.HttpClientErrorException;
//import org.springframework.web.client.HttpServerErrorException;
//import org.springframework.web.client.ResourceAccessException;
//import org.springframework.web.client.RestClientException;
//import org.springframework.web.client.RestTemplate;
//
//import java.net.ConnectException;
//import java.util.UUID;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.ArgumentMatchers.eq;
//import static org.mockito.Mockito.verify;
//import static org.mockito.Mockito.when;
//
//@ExtendWith(MockitoExtension.class)
//class BitvavoApiClientTest {
//
//    @Mock
//    private BitvavoConfig bitvavoConfig;
//
//    @Mock
//    private BitvavoAuthenticationService authenticationService;
//
//    @Mock
//    private RestTemplate restTemplate;
//
//    private BitvavoApiClient bitvavoApiClient;
//
//    @BeforeEach
//    void setUp() {
//        bitvavoApiClient = new BitvavoApiClient(restTemplate, bitvavoConfig, authenticationService);
//    }
//
//   @Test
//   void testGetRequest() {
//       // This test verifies that the authentication service is called with the correct parameters
//       // and that the API client correctly constructs the request
//
//       // Arrange
//       String endpoint = "/account";
//       BitvavoAuthHeaders mockHeaders = BitvavoAuthHeaders.builder()
//               .bitvavoBitvAvoAccessKey("mockKey")
//               .bitvavoBitvAvoAccessSignature("mockSignature")
//               .bitvavoBitvAvoAccessTimestamp("mockTimestamp")
//               .bitvavoBitvAvoAccessWindow("mockWindow")
//               .build();
//       when(bitvavoConfig.getApiUrl()).thenReturn("https://api.bitvavo.com/v2");
//       when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null)))
//               .thenReturn(mockHeaders);
//       when(restTemplate.exchange(
//               anyString(),
//               eq(HttpMethod.GET),
//               any(),
//               eq(Object.class)
//       )).thenReturn(ResponseEntity.ok().build());
//
//       // Act
//       bitvavoApiClient.get(endpoint, Object.class);
//
//       // Assert
//       verify(authenticationService).createAuthHeaders(eq("GET"), eq(endpoint), eq(null));
//       verify(restTemplate).exchange(
//               eq("https://api.bitvavo.com/v2" + endpoint),
//               eq(HttpMethod.GET),
//               any(),
//               eq(Object.class)
//       );
//   }
//
//    @Test
//    void testPostRequest() {
//        // This test verifies that the authentication service is called with the correct parameters
//        // and that the API client correctly constructs the request
//
//        // Arrange
//        String endpoint = "/order";
//        String body = "{\"market\":\"BTC-EUR\",\"side\":\"buy\",\"amount\":\"0.1\",\"orderId\":\"" + getRandomUUID() + "\"}";
//        when(bitvavoConfig.getApiUrl()).thenReturn("https://api.bitvavo.com/v2");
//        when(authenticationService.createAuthHeaders(eq("POST"), eq(endpoint), any()))
//                .thenReturn(BitvavoAuthHeaders.builder().build());
//        when(restTemplate.exchange(
//                anyString(),
//                eq(HttpMethod.POST),
//                any(),
//                eq(Object.class)
//        )).thenReturn(ResponseEntity.ok().build());
//
//        // Act
//        bitvavoApiClient.post(endpoint, body, Object.class);
//
//        // Assert
//        verify(authenticationService).createAuthHeaders(eq("POST"), eq(endpoint), any());
//        verify(restTemplate).exchange(
//                eq("https://api.bitvavo.com/v2" + endpoint),
//                eq(HttpMethod.POST),
//                any(),
//                eq(Object.class)
//        );
//    }
//
//    @Test
//    void testPostRequestWithInvalidBody() {
//        // This test verifies that an IllegalArgumentException is thrown when the request body is invalid
//
//        // Arrange
//        String endpoint = "/order";
//        Object invalidBody = new Object(); // This will cause JsonProcessingException
//
//        // Act & Assert
//        assertThrows(IllegalArgumentException.class, () -> bitvavoApiClient.post(endpoint, invalidBody, Object.class));
//    }
//
//    @Test
//    void testGetRequest_withHttpClientErrorException_propagatesException() {
//        // Arrange
//        String endpoint = "/account";
//        BitvavoAuthHeaders mockHeaders = BitvavoAuthHeaders.builder().build();
//        when(bitvavoConfig.getApiUrl()).thenReturn("https://api.bitvavo.com/v2");
//        when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null)))
//                .thenReturn(mockHeaders);
//        when(restTemplate.exchange(
//                anyString(),
//                eq(HttpMethod.GET),
//                any(),
//                eq(Object.class)
//        )).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));
//
//        // Act & Assert
//        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
//                () -> bitvavoApiClient.get(endpoint, Object.class));
//        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
//        assertEquals("400 Bad Request", exception.getMessage());
//    }
//
//    @Test
//    void testGetRequest_withHttpServerErrorException_propagatesException() {
//        // Arrange
//        String endpoint = "/account";
//        BitvavoAuthHeaders mockHeaders = BitvavoAuthHeaders.builder().build();
//        when(bitvavoConfig.getApiUrl()).thenReturn("https://api.bitvavo.com/v2");
//        when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null)))
//                .thenReturn(mockHeaders);
//        when(restTemplate.exchange(
//                anyString(),
//                eq(HttpMethod.GET),
//                any(),
//                eq(Object.class)
//        )).thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"));
//
//        // Act & Assert
//        HttpServerErrorException exception = assertThrows(HttpServerErrorException.class,
//                () -> bitvavoApiClient.get(endpoint, Object.class));
//        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
//        assertEquals("500 Internal Server Error", exception.getMessage());
//    }
//
//    @Test
//    void testGetRequest_withResourceAccessException_propagatesException() {
//        // Arrange
//        String endpoint = "/account";
//        BitvavoAuthHeaders mockHeaders = BitvavoAuthHeaders.builder().build();
//        when(bitvavoConfig.getApiUrl()).thenReturn("https://api.bitvavo.com/v2");
//        when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null)))
//                .thenReturn(mockHeaders);
//        when(restTemplate.exchange(
//                anyString(),
//                eq(HttpMethod.GET),
//                any(),
//                eq(Object.class)
//        )).thenThrow(new ResourceAccessException("Connection refused", new ConnectException("Connection refused")));
//
//        // Act & Assert
//        ResourceAccessException exception = assertThrows(ResourceAccessException.class,
//                () -> bitvavoApiClient.get(endpoint, Object.class));
//        assertEquals("Connection refused", exception.getMessage());
//    }
//
//    @Test
//    void testGetRequest_withRestClientException_propagatesException() {
//        // Arrange
//        String endpoint = "/account";
//        BitvavoAuthHeaders mockHeaders = BitvavoAuthHeaders.builder().build();
//        when(bitvavoConfig.getApiUrl()).thenReturn("https://api.bitvavo.com/v2");
//        when(authenticationService.createAuthHeaders(eq("GET"), eq(endpoint), eq(null)))
//                .thenReturn(mockHeaders);
//        when(restTemplate.exchange(
//                anyString(),
//                eq(HttpMethod.GET),
//                any(),
//                eq(Object.class)
//        )).thenThrow(new RestClientException("Unknown error"));
//
//        // Act & Assert
//        RestClientException exception = assertThrows(RestClientException.class,
//                () -> bitvavoApiClient.get(endpoint, Object.class));
//        assertEquals("Unknown error", exception.getMessage());
//    }
//
//    @Test
//    void testPostRequest_withHttpClientErrorException_propagatesException() {
//        // Arrange
//        String endpoint = "/order";
//        String body = "{\"market\":\"BTC-EUR\",\"side\":\"buy\",\"amount\":\"0.1\"}";
//        when(bitvavoConfig.getApiUrl()).thenReturn("https://api.bitvavo.com/v2");
//        when(authenticationService.createAuthHeaders(eq("POST"), eq(endpoint), any()))
//                .thenReturn(BitvavoAuthHeaders.builder().build());
//        when(restTemplate.exchange(
//                anyString(),
//                eq(HttpMethod.POST),
//                any(),
//                eq(Object.class)
//        )).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));
//
//        // Act & Assert
//        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
//                () -> bitvavoApiClient.post(endpoint, body, Object.class));
//        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
//        assertEquals("400 Bad Request", exception.getMessage());
//    }
//
//    private static UUID getRandomUUID() {
//        return UUID.randomUUID();
//    }
//}
