package nl.jimkaplan.autotrader.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class EncryptionServiceTest {

    private EncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        // Base64 encoded 32-byte key (123456789012345678901234567890123)
        String TEST_MASTER_KEY = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=";
        encryptionService = new EncryptionService(TEST_MASTER_KEY);
    }

    @Test
    void encrypt_withValidPlaintext_returnsEncryptedString() {
        // Arrange
        String plaintext = "This is a test plaintext";

        // Act
        String encrypted = encryptionService.encrypt(plaintext);

        // Assert
        assertNotNull(encrypted);
        assertNotEquals(plaintext, encrypted);
    }

    @Test
    void encrypt_withEmptyString_returnsEncryptedString() {
        // Arrange
        String plaintext = "";

        // Act
        String encrypted = encryptionService.encrypt(plaintext);

        // Assert
        assertNotNull(encrypted);
        assertNotEquals(plaintext, encrypted);
    }

    @Test
    void encrypt_withNullInput_throwsException() {
        // Act & Assert
        assertThrows(RuntimeException.class, () -> encryptionService.encrypt(null));
    }

    @Test
    void decrypt_withValidCiphertext_returnsOriginalPlaintext() {
        // Arrange
        String plaintext = "This is a test plaintext";
        String encrypted = encryptionService.encrypt(plaintext);

        // Act
        String decrypted = encryptionService.decrypt(encrypted);

        // Assert
        assertEquals(plaintext, decrypted);
    }

    @Test
    void decrypt_withEmptyString_throwsException() {
        // Act & Assert
        assertThrows(RuntimeException.class, () -> encryptionService.decrypt(""));
    }

    @Test
    void decrypt_withInvalidCiphertext_throwsException() {
        // Act & Assert
        assertThrows(RuntimeException.class, () -> encryptionService.decrypt("invalid-ciphertext"));
    }

    @Test
    void decrypt_withNullInput_throwsException() {
        // Act & Assert
        assertThrows(RuntimeException.class, () -> encryptionService.decrypt(null));
    }

    @Test
    void encryptAndDecrypt_withMultipleValues_worksCorrectly() {
        // Arrange
        String[] plaintexts = {
                "Short text",
                "A longer text with some special characters: !@#$%^&*()",
                "A very long text that exceeds the block size of the encryption algorithm. " +
                        "This is to ensure that the encryption and decryption work correctly with multiple blocks."
        };

        // Act & Assert
        for (String plaintext : plaintexts) {
            String encrypted = encryptionService.encrypt(plaintext);
            String decrypted = encryptionService.decrypt(encrypted);
            assertEquals(plaintext, decrypted);
        }
    }

    @Test
    void encrypt_withSamePlaintext_producesDifferentCiphertexts() {
        // Arrange
        String plaintext = "This is a test plaintext";

        // Act
        String encrypted1 = encryptionService.encrypt(plaintext);
        String encrypted2 = encryptionService.encrypt(plaintext);

        // Assert
        assertNotEquals(encrypted1, encrypted2, "Encrypting the same plaintext twice should produce different ciphertexts due to random IV");
    }
}
