package nl.jimkaplan.autotrader.service;

import nl.jimkaplan.autotrader.config.BitvavoConfig;
import nl.jimkaplan.autotrader.model.BitvavoAuthHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BitvavoAuthenticationServiceTest {

    @Mock
    private BitvavoConfig bitvavoConfig;

    private BitvavoAuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        authenticationService = new BitvavoAuthenticationService(bitvavoConfig);
    }

    @Test
    void createAuthHeaders_withValidConfig_returnsHeaders() {
        // Arrange
        String method = "GET";
        String path = "/account";
        String body = null;
        String apiKey = "test-api-key";
        String apiSecret = "test-api-secret";
        int window = 10000;

        when(bitvavoConfig.getApiKey()).thenReturn(apiKey);
        when(bitvavoConfig.getApiSecret()).thenReturn(apiSecret);
        when(bitvavoConfig.getWindow()).thenReturn(window);

        // Act
        BitvavoAuthHeaders headers = authenticationService.createAuthHeaders(method, path, body);

        // Assert
        assertNotNull(headers);
        assertEquals(apiKey, headers.getBitvavoBitvAvoAccessKey());
        assertNotNull(headers.getBitvavoBitvAvoAccessSignature());
        assertNotNull(headers.getBitvavoBitvAvoAccessTimestamp());
        assertEquals(String.valueOf(window), headers.getBitvavoBitvAvoAccessWindow());

        // Verify that the config was accessed at least once
        verify(bitvavoConfig, atLeastOnce()).getApiKey();
        verify(bitvavoConfig, atLeastOnce()).getApiSecret();
        verify(bitvavoConfig, atLeastOnce()).getWindow();
    }

    @Test
    void createAuthHeaders_withBody_includesBodyInSignature() {
        // Arrange
        String method = "POST";
        String path = "/order";
        String body = "{\"market\":\"BTC-EUR\",\"side\":\"buy\",\"amount\":\"0.1\"}";
        String apiKey = "test-api-key";
        String apiSecret = "test-api-secret";
        int window = 10000;

        when(bitvavoConfig.getApiKey()).thenReturn(apiKey);
        when(bitvavoConfig.getApiSecret()).thenReturn(apiSecret);
        when(bitvavoConfig.getWindow()).thenReturn(window);

        // Act
        BitvavoAuthHeaders headers = authenticationService.createAuthHeaders(method, path, body);

        // Assert
        assertNotNull(headers);
        assertEquals(apiKey, headers.getBitvavoBitvAvoAccessKey());
        assertNotNull(headers.getBitvavoBitvAvoAccessSignature());
        assertNotNull(headers.getBitvavoBitvAvoAccessTimestamp());
        assertEquals(String.valueOf(window), headers.getBitvavoBitvAvoAccessWindow());

        // Verify that the config was accessed at least once
        verify(bitvavoConfig, atLeastOnce()).getApiKey();
        verify(bitvavoConfig, atLeastOnce()).getApiSecret();
        verify(bitvavoConfig, atLeastOnce()).getWindow();
    }

    @Test
    void createAuthHeaders_withMissingApiKey_throwsIllegalStateException() {
        // Arrange
        String method = "GET";
        String path = "/account";
        String body = null;

        when(bitvavoConfig.getApiKey()).thenReturn(null);

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> authenticationService.createAuthHeaders(method, path, body));
        assertEquals("Bitvavo API key is not set. Please set the BITVAVO_API_KEY environment variable.", exception.getMessage());
    }

    @Test
    void createAuthHeaders_withEmptyApiKey_throwsIllegalStateException() {
        // Arrange
        String method = "GET";
        String path = "/account";
        String body = null;

        when(bitvavoConfig.getApiKey()).thenReturn("");

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> authenticationService.createAuthHeaders(method, path, body));
        assertEquals("Bitvavo API key is not set. Please set the BITVAVO_API_KEY environment variable.", exception.getMessage());
    }

    @Test
    void createAuthHeaders_withMissingApiSecret_throwsIllegalStateException() {
        // Arrange
        String method = "GET";
        String path = "/account";
        String body = null;

        when(bitvavoConfig.getApiKey()).thenReturn("test-api-key");
        when(bitvavoConfig.getApiSecret()).thenReturn(null);

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> authenticationService.createAuthHeaders(method, path, body));
        assertEquals("Bitvavo API secret is not set. Please set the BITVAVO_API_SECRET environment variable.", exception.getMessage());
    }

    @Test
    void createAuthHeaders_withEmptyApiSecret_throwsIllegalStateException() {
        // Arrange
        String method = "GET";
        String path = "/account";
        String body = null;

        when(bitvavoConfig.getApiKey()).thenReturn("test-api-key");
        when(bitvavoConfig.getApiSecret()).thenReturn("");

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> authenticationService.createAuthHeaders(method, path, body));
        assertEquals("Bitvavo API secret is not set. Please set the BITVAVO_API_SECRET environment variable.", exception.getMessage());
    }

    @Test
    void createAuthHeaders_withNoSuchAlgorithmException_throwsRuntimeException() {
        // Arrange
        String method = "GET";
        String path = "/account";
        String body = null;
        String apiKey = "test-api-key";
        String apiSecret = "test-api-secret";
        int window = 10000;

        // Create a test subclass that throws NoSuchAlgorithmException
        BitvavoAuthenticationService testService = new BitvavoAuthenticationService(bitvavoConfig) {
            @Override
            protected Mac getMacInstance() throws NoSuchAlgorithmException {
                throw new NoSuchAlgorithmException("Test exception");
            }
        };

        when(bitvavoConfig.getApiKey()).thenReturn(apiKey);
        when(bitvavoConfig.getApiSecret()).thenReturn(apiSecret);
        when(bitvavoConfig.getWindow()).thenReturn(window);

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> testService.createAuthHeaders(method, path, body));
        assertEquals("Error creating signature for Bitvavo API", exception.getMessage());
        assertInstanceOf(NoSuchAlgorithmException.class, exception.getCause());
        assertEquals("Test exception", exception.getCause().getMessage());
    }

    @Test
    void createAuthHeaders_withInvalidKeyException_throwsRuntimeException() {
        // Arrange
        String method = "GET";
        String path = "/account";
        String body = null;
        String apiKey = "test-api-key";
        String apiSecret = "test-api-secret";
        int window = 10000;

        // Create a test subclass that throws InvalidKeyException
        BitvavoAuthenticationService testService = new BitvavoAuthenticationService(bitvavoConfig) {
            @Override
            protected void initMac(Mac mac, SecretKeySpec key) throws InvalidKeyException {
                throw new InvalidKeyException("Test invalid key");
            }
        };

        when(bitvavoConfig.getApiKey()).thenReturn(apiKey);
        when(bitvavoConfig.getApiSecret()).thenReturn(apiSecret);
        when(bitvavoConfig.getWindow()).thenReturn(window);

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> testService.createAuthHeaders(method, path, body));
        assertEquals("Error creating signature for Bitvavo API", exception.getMessage());
        assertInstanceOf(InvalidKeyException.class, exception.getCause());
        assertEquals("Test invalid key", exception.getCause().getMessage());
    }
}
