package nl.jimkaplan.autotrader.bitvavo.service;

import nl.jimkaplan.autotrader.bitvavo.model.BitvavoAuthHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BitvavoAuthenticationServiceTest {

    @Spy
    @InjectMocks
    private BitvavoAuthenticationService authService;

    private final String TEST_API_KEY = "test-api-key";
    private final String TEST_API_SECRET = "test-api-secret";
    private final String TEST_METHOD = "GET";
    private final String TEST_PATH = "/account";
    private final String TEST_BODY = "{\"key\":\"value\"}";
    private final String EMPTY_BODY = "";

    private Mac mockMac;
    private SecretKeySpec mockKeySpec;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        mockMac = mock(Mac.class);
        mockKeySpec = mock(SecretKeySpec.class);
    }

    @Test
    void createAuthHeaders_withValidInputs_returnsHeaders() {
        // Act
        BitvavoAuthHeaders headers = authService.createAuthHeaders(
                TEST_METHOD, TEST_PATH, TEST_BODY, TEST_API_KEY, TEST_API_SECRET);

        // Assert
        assertNotNull(headers);
        assertEquals(TEST_API_KEY, headers.getBitvavoBitvAvoAccessKey());
        assertNotNull(headers.getBitvavoBitvAvoAccessSignature());
        assertNotNull(headers.getBitvavoBitvAvoAccessTimestamp());
        assertEquals(BitvavoAuthenticationService.ACCESS_WINDOW, headers.getBitvavoBitvAvoAccessWindow());
    }

    @Test
    void createAuthHeaders_withEmptyBody_returnsHeaders() {
        // Act
        BitvavoAuthHeaders headers = authService.createAuthHeaders(
                TEST_METHOD, TEST_PATH, EMPTY_BODY, TEST_API_KEY, TEST_API_SECRET);

        // Assert
        assertNotNull(headers);
        assertEquals(TEST_API_KEY, headers.getBitvavoBitvAvoAccessKey());
        assertNotNull(headers.getBitvavoBitvAvoAccessSignature());
        assertNotNull(headers.getBitvavoBitvAvoAccessTimestamp());
        assertEquals(BitvavoAuthenticationService.ACCESS_WINDOW, headers.getBitvavoBitvAvoAccessWindow());
    }

    @Test
    void createAuthHeaders_withNullBody_returnsHeaders() {
        // Act
        BitvavoAuthHeaders headers = authService.createAuthHeaders(
                TEST_METHOD, TEST_PATH, null, TEST_API_KEY, TEST_API_SECRET);

        // Assert
        assertNotNull(headers);
        assertEquals(TEST_API_KEY, headers.getBitvavoBitvAvoAccessKey());
        assertNotNull(headers.getBitvavoBitvAvoAccessSignature());
        assertNotNull(headers.getBitvavoBitvAvoAccessTimestamp());
        assertEquals(BitvavoAuthenticationService.ACCESS_WINDOW, headers.getBitvavoBitvAvoAccessWindow());
    }

    @Test
    void createAuthHeaders_withNullApiKey_throwsException() {
        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                authService.createAuthHeaders(TEST_METHOD, TEST_PATH, TEST_BODY, null, TEST_API_SECRET));
        
        assertEquals("Bitvavo API key is not set. Please set the BITVAVO_API_KEY environment variable.", exception.getMessage());
    }

    @Test
    void createAuthHeaders_withEmptyApiKey_throwsException() {
        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                authService.createAuthHeaders(TEST_METHOD, TEST_PATH, TEST_BODY, "", TEST_API_SECRET));
        
        assertEquals("Bitvavo API key is not set. Please set the BITVAVO_API_KEY environment variable.", exception.getMessage());
    }

    @Test
    void createAuthHeaders_withNullApiSecret_throwsException() {
        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                authService.createAuthHeaders(TEST_METHOD, TEST_PATH, TEST_BODY, TEST_API_KEY, null));
        
        assertEquals("Bitvavo API secret is not set. Please set the BITVAVO_API_SECRET environment variable.", exception.getMessage());
    }

    @Test
    void createAuthHeaders_withEmptyApiSecret_throwsException() {
        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                authService.createAuthHeaders(TEST_METHOD, TEST_PATH, TEST_BODY, TEST_API_KEY, ""));
        
        assertEquals("Bitvavo API secret is not set. Please set the BITVAVO_API_SECRET environment variable.", exception.getMessage());
    }

    @Test
    void getMacInstance_returnsValidMac() throws NoSuchAlgorithmException {
        // Act
        Mac mac = authService.getMacInstance();
        
        // Assert
        assertNotNull(mac);
        assertEquals("HmacSHA256", mac.getAlgorithm());
    }

    @Test
    void createSecretKeySpec_returnsValidKeySpec() {
        // Act
        SecretKeySpec keySpec = authService.createSecretKeySpec(TEST_API_SECRET);
        
        // Assert
        assertNotNull(keySpec);
        assertEquals("HmacSHA256", keySpec.getAlgorithm());
        assertArrayEquals(TEST_API_SECRET.getBytes(StandardCharsets.UTF_8), keySpec.getEncoded());
    }

    @Test
    void initMac_initializesMacCorrectly() throws InvalidKeyException, NoSuchAlgorithmException {
        // Arrange
        Mac mac = authService.getMacInstance();
        SecretKeySpec keySpec = authService.createSecretKeySpec(TEST_API_SECRET);
        
        // Act
        authService.initMac(mac, keySpec);
        
        // Assert - no exception means success
    }

    @Test
    void doFinal_computesHashCorrectly() throws NoSuchAlgorithmException, InvalidKeyException {
        // Arrange
        Mac mac = authService.getMacInstance();
        SecretKeySpec keySpec = authService.createSecretKeySpec(TEST_API_SECRET);
        authService.initMac(mac, keySpec);
        String message = "test-message";
        
        // Act
        byte[] hash = authService.doFinal(mac, message);
        
        // Assert
        assertNotNull(hash);
        assertTrue(hash.length > 0);
    }

    @Test
    void createSignature_withCryptoException_throwsRuntimeException() throws NoSuchAlgorithmException {
        // Arrange
        doThrow(new NoSuchAlgorithmException("Test exception")).when(authService).getMacInstance();
        
        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                authService.createAuthHeaders(TEST_METHOD, TEST_PATH, TEST_BODY, TEST_API_KEY, TEST_API_SECRET));
        
        assertEquals("Error creating signature for Bitvavo API", exception.getMessage());
        assertTrue(exception.getCause() instanceof NoSuchAlgorithmException);
    }

    @Test
    void createSignature_withInvalidKeyException_throwsRuntimeException() throws NoSuchAlgorithmException, InvalidKeyException {
        // Arrange
        doThrow(new InvalidKeyException("Test exception")).when(authService).initMac(any(Mac.class), any(SecretKeySpec.class));
        
        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                authService.createAuthHeaders(TEST_METHOD, TEST_PATH, TEST_BODY, TEST_API_KEY, TEST_API_SECRET));
        
        assertEquals("Error creating signature for Bitvavo API", exception.getMessage());
        assertTrue(exception.getCause() instanceof InvalidKeyException);
    }
}