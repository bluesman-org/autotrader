package nl.jimkaplan.autotrader.service;

import nl.jimkaplan.autotrader.controller.BitvavoCredentialsResponse;
import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.repository.BitvavoCredentialsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The system-wide key: stored encrypted, handed out decrypted, and never revealed by the masked
 * view. A real {@link EncryptionService} runs here, so a stored value is checked by decrypting it.
 */
@ExtendWith(MockitoExtension.class)
class BitvavoCredentialsServiceTest {

    private static final String MASTER_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Mock
    private BitvavoCredentialsRepository repository;

    private EncryptionService encryptionService;
    private BitvavoCredentialsService service;

    @BeforeEach
    void setUp() {
        encryptionService = new EncryptionService(MASTER_KEY);
        service = new BitvavoCredentialsService(repository, encryptionService);
    }

    @Test
    void save_encryptsTheKeyAndSecretUnderTheSingletonId() {
        when(repository.save(any(BitvavoCredentials.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BitvavoCredentials stored = service.save(" key ", " secret ", 7L);

        assertEquals(BitvavoCredentials.ID, stored.getId());
        assertNotEquals(" key ", stored.getEncryptedApiKey());
        assertNotEquals(" secret ", stored.getEncryptedApiSecret());
        // The whitespace is trimmed and both values survive a round trip.
        assertEquals("key", encryptionService.decrypt(stored.getEncryptedApiKey()));
        assertEquals("secret", encryptionService.decrypt(stored.getEncryptedApiSecret()));
        assertEquals(7L, stored.getOperatorId());
        assertEquals(1, stored.getKeyVersion());
        assertNotNull(stored.getUpdatedTime());
    }

    @Test
    void get_fillsThePlaintextAndConfiguredFollowsTheStore() {
        when(repository.findById(BitvavoCredentials.ID)).thenReturn(Optional.of(stored("key")));
        when(repository.existsById(BitvavoCredentials.ID)).thenReturn(true);

        BitvavoCredentials credentials = service.get().orElseThrow();

        assertEquals("key", credentials.getApiKey());
        assertEquals("secret", credentials.getApiSecret());
        assertTrue(service.configured());
    }

    @Test
    void get_isEmptyWhenNothingIsStored() {
        when(repository.findById(BitvavoCredentials.ID)).thenReturn(Optional.empty());
        when(repository.existsById(BitvavoCredentials.ID)).thenReturn(false);

        assertTrue(service.get().isEmpty());
        assertFalse(service.configured());
        assertTrue(service.hint().isEmpty());
        assertFalse(service.view().configured());
        assertEquals(null, service.view().apiKeyHint());
    }

    @Test
    void save_rejectsAMissingKeyOrSecret() {
        assertThrows(IllegalArgumentException.class, () -> service.save(null, "secret", 1L));
        assertThrows(IllegalArgumentException.class, () -> service.save("key", " ", 1L));
    }

    @Test
    void hint_identifiesTheKeyWithoutRevealingIt() {
        when(repository.findById(BitvavoCredentials.ID)).thenReturn(Optional.of(stored("abcdef-1234")));
        assertEquals(Optional.of("…1234"), service.hint());

        when(repository.findById(BitvavoCredentials.ID)).thenReturn(Optional.of(stored("ab")));
        assertEquals(Optional.of("…"), service.hint());

        when(repository.findById(BitvavoCredentials.ID)).thenReturn(Optional.of(stored(null)));
        assertEquals(Optional.of("…"), service.hint());
    }

    @Test
    void view_carriesTheMaskedStateOnly() {
        when(repository.findById(BitvavoCredentials.ID))
                .thenReturn(Optional.of(stored("abcdef-1234")));

        BitvavoCredentialsResponse view = service.view();

        assertTrue(view.configured());
        assertEquals("…1234", view.apiKeyHint());
        assertEquals(7L, view.operatorId());
        assertEquals(Instant.parse("2026-10-16T08:00:00Z"), view.updatedTime());
    }

    private BitvavoCredentials stored(String apiKey) {
        return BitvavoCredentials.builder()
                .id(BitvavoCredentials.ID)
                .encryptedApiKey(apiKey == null ? null : encryptionService.encrypt(apiKey))
                .encryptedApiSecret(encryptionService.encrypt("secret"))
                .operatorId(7L)
                .updatedTime(Instant.parse("2026-10-16T08:00:00Z"))
                .build();
    }
}
