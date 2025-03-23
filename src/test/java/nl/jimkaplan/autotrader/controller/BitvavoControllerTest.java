package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.model.Fills;
import nl.jimkaplan.autotrader.model.GetAccountFeesResponse;
import nl.jimkaplan.autotrader.model.GetAssetDataResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BitvavoControllerTest {

    @Mock
    private BitvavoApiClient bitvavoApiClient;

    private BitvavoController bitvavoController;

    @BeforeEach
    void setUp() {
        bitvavoController = new BitvavoController(bitvavoApiClient);
    }

    @Test
    void testGetAccount() {
        // Arrange
        GetAccountFeesResponse apiResponse = new GetAccountFeesResponse(
                new GetAccountFeesResponse.Fees(
                        new BigDecimal("0.0025"),
                        new BigDecimal("0.0015"),
                        new BigDecimal("10000.01")
                )
        );
        when(bitvavoApiClient.get(eq("/account"), eq(GetAccountFeesResponse.class))).thenReturn(apiResponse);

        // Create expected response
        GetAccountFeesResponse.Fees fees = new GetAccountFeesResponse.Fees(
                BigDecimal.valueOf(0.0025), BigDecimal.valueOf(0.0015), BigDecimal.valueOf(10000.01));
        GetAccountFeesResponse expectedResponse = new GetAccountFeesResponse(fees);

        // Act
        GetAccountFeesResponse result = bitvavoController.getAccount();

        // Assert
        assertEquals(expectedResponse, result);
        assertEquals(BigDecimal.valueOf(0.0025), result.getFees().getTaker());
        assertEquals(BigDecimal.valueOf(0.0015), result.getFees().getMaker());
        assertEquals(BigDecimal.valueOf(10000.01), result.getFees().getVolume());
        verify(bitvavoApiClient).get(eq("/account"), eq(GetAccountFeesResponse.class));
    }

    @Test
    void testGetServerTime() {
        // Arrange
        Object expectedResponse = Map.of("time", System.currentTimeMillis());
        when(bitvavoApiClient.get(eq("/time"), eq(Object.class))).thenReturn(expectedResponse);

        // Act
        Object result = bitvavoController.getServerTime();

        // Assert
        assertEquals(expectedResponse, result);
        verify(bitvavoApiClient).get(eq("/time"), eq(Object.class));
    }

    @Test
    void testCreateOrder() {
        // Arrange
        CreateOrderRequest request = CreateOrderRequest.builder()
                .market("BTC-EUR")
                .side("buy")
                .orderType("limit")
                .amount(new BigDecimal("0.1"))
                .price(new BigDecimal("20000"))
                .timeInForce("GTC")
                .postOnly(false)
                .selfTradePrevention("decrementAndCancel")
                .responseRequired(true)
                .build();

        CreateOrderResponse expectedResponse = new CreateOrderResponse();
        UUID orderId = new UUID(0, 0);
        expectedResponse.setOrderId(orderId);
        expectedResponse.setMarket("BTC-EUR");
        Instant now = Instant.now();
        expectedResponse.setCreated(now);
        expectedResponse.setUpdated(now);
        expectedResponse.setStatus("new");
        expectedResponse.setSide("buy");
        expectedResponse.setOrderType("limit");
        expectedResponse.setAmount(new BigDecimal("0.1"));
        expectedResponse.setPrice(new BigDecimal("20000"));
        expectedResponse.setAmountRemaining(new BigDecimal("0.1"));
        expectedResponse.setOnHold(new BigDecimal("0.1"));
        expectedResponse.setOnHoldCurrency("BTC");
        expectedResponse.setVisible(true);
        expectedResponse.setTimeInForce("GTC");
        expectedResponse.setPostOnly(false);
        expectedResponse.setSelfTradePrevention("decrementAndCancel");

        // Add fills to test the Fills class
        Fills fill = new Fills();
        fill.setId(new UUID(0, 1));
        fill.setTimestamp(now);
        fill.setAmount(new BigDecimal("0.0"));
        fill.setPrice(new BigDecimal("20000"));
        fill.setTaker(false);
        fill.setFee(new BigDecimal("0.0"));
        fill.setFeeCurrency("EUR");
        fill.setSettled(true);
        expectedResponse.setFills(List.of(fill));

        when(bitvavoApiClient.post(eq("/order"), eq(request), eq(CreateOrderResponse.class)))
                .thenReturn(expectedResponse);

        // Act
        CreateOrderResponse result = bitvavoController.createOrder(request);

        // Assert
        assertEquals(expectedResponse, result);
        assertEquals(orderId, result.getOrderId());
        assertEquals("BTC-EUR", result.getMarket());
        assertEquals(now, result.getCreated());
        assertEquals(now, result.getUpdated());
        assertEquals("new", result.getStatus());
        assertEquals("buy", result.getSide());
        assertEquals("limit", result.getOrderType());
        assertEquals(new BigDecimal("0.1"), result.getAmount());
        assertEquals(new BigDecimal("20000"), result.getPrice());
        assertEquals(new BigDecimal("0.1"), result.getAmountRemaining());
        assertEquals(new BigDecimal("0.1"), result.getOnHold());
        assertEquals("BTC", result.getOnHoldCurrency());
        assertEquals(true, result.getVisible());
        assertEquals("GTC", result.getTimeInForce());
        assertEquals(false, result.getPostOnly());
        assertEquals("decrementAndCancel", result.getSelfTradePrevention());
        assertEquals(false, result.getDisableMarketProtection()); // Test the custom getter

        // Test fills
        assertEquals(1, result.getFills().size());
        Fills resultFill = result.getFills().getFirst();
        assertEquals(new UUID(0, 1), resultFill.getId());
        assertEquals(now, resultFill.getTimestamp());
        assertEquals(new BigDecimal("0.0"), resultFill.getAmount());
        assertEquals(new BigDecimal("20000"), resultFill.getPrice());
        assertEquals(false, resultFill.getTaker());
        assertEquals(new BigDecimal("0.0"), resultFill.getFee());
        assertEquals("EUR", resultFill.getFeeCurrency());
        assertEquals(true, resultFill.getSettled());

        verify(bitvavoApiClient).post(eq("/order"), eq(request), eq(CreateOrderResponse.class));
    }

    @Test
    void testGetAccount_withHttpClientErrorException_propagatesException() {
        // Arrange
        when(bitvavoApiClient.get(eq("/account"), eq(GetAccountFeesResponse.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));

        // Act & Assert
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                () -> bitvavoController.getAccount());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("400 Bad Request", exception.getMessage());
    }

    @Test
    void testGetAccount_withHttpServerErrorException_propagatesException() {
        // Arrange
        when(bitvavoApiClient.get(eq("/account"), eq(GetAccountFeesResponse.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"));

        // Act & Assert
        HttpServerErrorException exception = assertThrows(HttpServerErrorException.class,
                () -> bitvavoController.getAccount());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
        assertEquals("500 Internal Server Error", exception.getMessage());
    }

    @Test
    void testGetAccount_withResourceAccessException_propagatesException() {
        // Arrange
        when(bitvavoApiClient.get(eq("/account"), eq(GetAccountFeesResponse.class)))
                .thenThrow(new ResourceAccessException("Connection refused", new ConnectException("Connection refused")));

        // Act & Assert
        ResourceAccessException exception = assertThrows(ResourceAccessException.class,
                () -> bitvavoController.getAccount());
        assertEquals("Connection refused", exception.getMessage());
    }

    @Test
    void testGetAccount_withRestClientException_propagatesException() {
        // Arrange
        when(bitvavoApiClient.get(eq("/account"), eq(GetAccountFeesResponse.class)))
                .thenThrow(new RestClientException("Unknown error"));

        // Act & Assert
        RestClientException exception = assertThrows(RestClientException.class,
                () -> bitvavoController.getAccount());
        assertEquals("Unknown error", exception.getMessage());
    }

    @Test
    void testCreateOrder_withHttpClientErrorException_propagatesException() {
        // Arrange
        CreateOrderRequest request = CreateOrderRequest.builder()
                .market("BTC-EUR")
                .side("buy")
                .amount(new BigDecimal("0.1"))
                .build();

        when(bitvavoApiClient.post(eq("/order"), eq(request), eq(CreateOrderResponse.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));

        // Act & Assert
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                () -> bitvavoController.createOrder(request));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("400 Bad Request", exception.getMessage());
    }

    @Test
    void testGetAssetData_withNoSuchElementException_propagatesException() {
        // Arrange
        String symbol = "INVALID";
        when(bitvavoApiClient.get(eq("/assets?symbol=" + symbol), eq(Object.class)))
                .thenThrow(new NoSuchElementException("Asset not found: " + symbol));

        // Act & Assert
        NoSuchElementException exception = assertThrows(NoSuchElementException.class,
                () -> bitvavoController.getAssetData(symbol));
        assertEquals("Asset not found: " + symbol, exception.getMessage());
    }

    @Test
    void testGetAssetData_withNoSymbol_returnsAllAssets() {
        // Arrange
        List<Map<String, Object>> apiResponse = List.of(
                Map.of(
                        "symbol", "BTC",
                        "name", "Bitcoin",
                        "decimals", 8,
                        "depositFee", "0",
                        "depositConfirmations", 2,
                        "depositStatus", "OK",
                        "withdrawalFee", "0.0001",
                        "withdrawalMinAmount", "0.001",
                        "withdrawalStatus", "OK",
                        "networks", List.of("BTC")
                ),
                Map.of(
                        "symbol", "ETH",
                        "name", "Ethereum",
                        "decimals", 18,
                        "depositFee", "0",
                        "depositConfirmations", 15,
                        "depositStatus", "OK",
                        "withdrawalFee", "0.005",
                        "withdrawalMinAmount", "0.01",
                        "withdrawalStatus", "OK",
                        "networks", List.of("ETH")
                )
        );

        when(bitvavoApiClient.get(eq("/assets"), eq(Object.class))).thenReturn(apiResponse);

        // Act
        List<GetAssetDataResponse> result = bitvavoController.getAssetData(null);

        // Assert
        assertEquals(2, result.size());
        assertEquals("BTC", result.getFirst().getSymbol());
        assertEquals("Bitcoin", result.getFirst().getName());
        assertEquals(8, result.getFirst().getDecimals());
        assertEquals(new BigDecimal("0"), result.getFirst().getDepositFee());
        assertEquals(2, result.getFirst().getDepositConfirmations());
        assertEquals("OK", result.getFirst().getDepositStatus());
        assertEquals(new BigDecimal("0.0001"), result.getFirst().getWithdrawalFee());
        assertEquals(new BigDecimal("0.001"), result.getFirst().getWithdrawalMinAmount());
        assertEquals("OK", result.get(0).getWithdrawalStatus());
        assertEquals(List.of("BTC"), result.get(0).getNetworks());

        assertEquals("ETH", result.get(1).getSymbol());
        assertEquals("Ethereum", result.get(1).getName());

        verify(bitvavoApiClient).get(eq("/assets"), eq(Object.class));
    }

    @Test
    void testGetAssetData_withEmptySymbol_returnsAllAssets() {
        // Arrange
        List<Map<String, Object>> apiResponse = List.of(
                Map.of(
                        "symbol", "BTC",
                        "name", "Bitcoin"
                )
        );

        when(bitvavoApiClient.get(eq("/assets"), eq(Object.class))).thenReturn(apiResponse);

        // Act
        List<GetAssetDataResponse> result = bitvavoController.getAssetData("");

        // Assert
        assertEquals(1, result.size());
        assertEquals("BTC", result.getFirst().getSymbol());
        assertEquals("Bitcoin", result.getFirst().getName());

        verify(bitvavoApiClient).get(eq("/assets"), eq(Object.class));
    }

    @Test
    void testGetAssetData_withValidSymbol_returnsSingleAsset() {
        // Arrange
        String symbol = "BTC";
        Map<String, Object> apiResponse = Map.of(
                "symbol", "BTC",
                "name", "Bitcoin",
                "decimals", 8,
                "depositFee", "0",
                "depositConfirmations", 2,
                "depositStatus", "OK",
                "withdrawalFee", "0.0001",
                "withdrawalMinAmount", "0.001",
                "withdrawalStatus", "OK",
                "networks", List.of("BTC")
        );

        when(bitvavoApiClient.get(eq("/assets?symbol=" + symbol), eq(Object.class))).thenReturn(apiResponse);

        // Act
        List<GetAssetDataResponse> result = bitvavoController.getAssetData(symbol);

        // Assert
        assertEquals(1, result.size());
        assertEquals("BTC", result.getFirst().getSymbol());
        assertEquals("Bitcoin", result.getFirst().getName());
        assertEquals(8, result.getFirst().getDecimals());
        assertEquals(new BigDecimal("0"), result.getFirst().getDepositFee());
        assertEquals(2, result.getFirst().getDepositConfirmations());
        assertEquals("OK", result.getFirst().getDepositStatus());
        assertEquals(new BigDecimal("0.0001"), result.getFirst().getWithdrawalFee());
        assertEquals(new BigDecimal("0.001"), result.getFirst().getWithdrawalMinAmount());
        assertEquals("OK", result.getFirst().getWithdrawalStatus());
        assertEquals(List.of("BTC"), result.getFirst().getNetworks());

        verify(bitvavoApiClient).get(eq("/assets?symbol=" + symbol), eq(Object.class));
    }
}
