package nl.jimkaplan.autotrader.strategy.service;

import com.zerodeplibs.webpush.VAPIDKeyPair;
import com.zerodeplibs.webpush.VAPIDKeyPairs;
import com.zerodeplibs.webpush.key.PrivateKeySources;
import com.zerodeplibs.webpush.key.PublicKeySources;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.repository.AppUserRepository;
import nl.jimkaplan.autotrader.repository.PushSettingsRepository;
import nl.jimkaplan.autotrader.service.EncryptionService;
import nl.jimkaplan.autotrader.strategy.model.document.PushSettings;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Owns the installation's VAPID key pair and the contact address the push services may use.
 *
 * <p>The pair is generated once on the first request for the public key and stored encrypted, so a
 * browser subscription survives restarts and deployments. Rotating it (deleting the
 * {@code push_settings} document) invalidates every existing subscription; the settings page asks
 * the operator to enable notifications again on each device afterwards.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushSettingsService {

    private static final String CURVE = "secp256r1";
    private static final String FALLBACK_SUBJECT = "mailto:noreply@localhost";

    private final PushSettingsRepository pushSettingsRepository;
    private final EncryptionService encryptionService;
    private final AppUserRepository appUserRepository;

    /**
     * @return True when the pair has been generated; the first request for the public key creates it
     */
    public boolean configured() {
        return pushSettingsRepository.existsById(PushSettings.ID);
    }

    /**
     * Build the key pair from storage, generating and saving it on the first call.
     *
     * @return The VAPID key pair
     * @throws IllegalStateException when the stored key material cannot be read
     */
    public synchronized VAPIDKeyPair keyPair() {
        PushSettings settings = pushSettingsRepository.findById(PushSettings.ID)
                .orElseGet(this::generate);
        try {
            byte[] privateKey = Base64.getDecoder().decode(
                    encryptionService.decrypt(settings.getEncryptedPrivateKey()));
            byte[] publicKey = Base64.getDecoder().decode(settings.getPublicKey());
            return VAPIDKeyPairs.of(PrivateKeySources.ofPKCS8Bytes(privateKey),
                    PublicKeySources.ofX509Bytes(publicKey));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("The stored Web Push key material cannot be read; delete "
                    + "the push_settings document to generate a new pair and re-enable notifications "
                    + "on each device.", ex);
        }
    }

    /**
     * @return The browser-facing public key in the uncompressed form PushManager expects
     */
    public byte[] publicKey() {
        return keyPair().extractPublicKeyInUncompressedForm();
    }

    /**
     * The VAPID JWT subject: the account's recovery address, as a {@code mailto:} contact. Push
     * services use it only to contact the operator about a misbehaving sender.
     *
     * @return The subject, or a placeholder when no account address is set
     */
    public String subject() {
        List<AppUser> users = appUserRepository.findAll();
        return users.stream()
                .map(AppUser::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .findFirst()
                .map(email -> "mailto:" + email)
                .orElse(FALLBACK_SUBJECT);
    }

    private PushSettings generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(CURVE));
            KeyPair pair = generator.generateKeyPair();
            String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            PushSettings settings = PushSettings.builder()
                    .id(PushSettings.ID)
                    .encryptedPrivateKey(encryptionService.encrypt(privateKey))
                    .publicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                    .createdTime(Instant.now())
                    .build();
            PushSettings saved = pushSettingsRepository.save(settings);
            log.info("Generated the Web Push VAPID key pair");
            return saved;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not generate the Web Push key pair", ex);
        }
    }
}
