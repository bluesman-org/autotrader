package nl.jimkaplan.autotrader.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service for encrypting and decrypting sensitive data.
 * Uses AES-256 encryption in GCM mode for strong security with authentication.
 */
@Service
public class EncryptionService {
    private final String masterKey;
    private final SecureRandom secureRandom;

    public EncryptionService(@Value("${encryption.master-key}") String masterKey) {
        this.masterKey = masterKey;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Encrypts the given plaintext using AES-256 in GCM mode.
     *
     * @param plaintext The text to encrypt
     * @return Base64-encoded encrypted string
     */
    public String encrypt(String plaintext) {
        // Generate a random IV (Initialization Vector)
        byte[] iv = new byte[12]; // 96 bits for GCM
        secureRandom.nextBytes(iv);

        try {
            // Create cipher instance
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            // Create secret key
            SecretKey key = new SecretKeySpec(
                    Base64.getDecoder().decode(masterKey), "AES");

            // Initialize cipher for encryption
            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

            // Encrypt
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Combine IV and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            // Encode as Base64 string
            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts the given ciphertext using AES-256 in GCM mode.
     *
     * @param ciphertext Base64-encoded encrypted string
     * @return Decrypted plaintext
     */
    public String decrypt(String ciphertext) {
        try {
            // Decode from Base64
            byte[] ciphertextBytes = Base64.getDecoder().decode(ciphertext);

            // Extract IV and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.wrap(ciphertextBytes);
            byte[] iv = new byte[12];
            byteBuffer.get(iv);
            byte[] encrypted = new byte[byteBuffer.remaining()];
            byteBuffer.get(encrypted);

            // Create cipher instance
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            // Create secret key
            SecretKey key = new SecretKeySpec(
                    Base64.getDecoder().decode(masterKey), "AES");

            // Initialize cipher for decryption
            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

            // Decrypt
            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}