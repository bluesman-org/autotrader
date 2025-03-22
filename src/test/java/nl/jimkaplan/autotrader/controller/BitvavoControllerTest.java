package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.model.Fills;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Map<String, Object> expectedResponse = Map.of("key", "value");
        when(bitvavoApiClient.get(eq("/account"), eq(Map.class))).thenReturn(expectedResponse);

        // Act
        Map<String, Object> result = bitvavoController.getAccount();

        // Assert
        assertEquals(expectedResponse, result);
        verify(bitvavoApiClient).get(eq("/account"), eq(Map.class));
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
}
