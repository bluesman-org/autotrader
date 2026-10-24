package nl.jimkaplan.autotrader.strategy.service;

import com.zerodeplibs.webpush.VAPIDKeyPair;
import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.repository.AppUserRepository;
import nl.jimkaplan.autotrader.repository.PushSettingsRepository;
import nl.jimkaplan.autotrader.service.EncryptionService;
import nl.jimkaplan.autotrader.strategy.model.document.PushSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushSettingsServiceTest {

    @Mock
    private PushSettingsRepository repository;

    @Mock
    private AppUserRepository users;

    private PushSettingsService service;

    @BeforeEach
    void setUp() {
        EncryptionService encryption = new EncryptionService(
                Base64.getEncoder().encodeToString(new byte[32]));
        service = new PushSettingsService(repository, encryption, users);
    }

    @Test
    void theKeyPairIsGeneratedOnceAndItsPrivateKeyIsEncrypted() {
        AtomicReference<PushSettings> saved = new AtomicReference<>();
        when(repository.findById(PushSettings.ID))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(repository.save(any(PushSettings.class))).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        VAPIDKeyPair first = service.keyPair();
        VAPIDKeyPair second = service.keyPair();

        ArgumentCaptor<PushSettings> captor = ArgumentCaptor.forClass(PushSettings.class);
        verify(repository, times(1)).save(captor.capture());
        PushSettings stored = captor.getValue();
        assertEquals(PushSettings.ID, stored.getId());
        assertTrue(stored.getEncryptedPrivateKey().length() > 20);
        assertFalse(stored.getEncryptedPrivateKey().contains("PRIVATE"));
        assertTrue(stored.getPublicKey().length() > 20);
        assertNotEquals(stored.getPublicKey(), stored.getEncryptedPrivateKey());
        assertArrayEquals(first.extractPublicKeyInUncompressedForm(),
                second.extractPublicKeyInUncompressedForm());
        assertFalse(service.publicKey().length == 0);
    }

    @Test
    void configuredAndMissingKeyMaterialBehaveAsDocumented() {
        when(repository.existsById(PushSettings.ID)).thenReturn(true);
        assertTrue(service.configured());

        when(repository.existsById(PushSettings.ID)).thenReturn(false);
        assertFalse(service.configured());
    }

    @Test
    void anUnreadableStoredPrivateKeyIsAnActionableFailure() {
        when(repository.findById(PushSettings.ID)).thenReturn(Optional.of(PushSettings.builder()
                .id(PushSettings.ID)
                .encryptedPrivateKey("not-base64")
                .publicKey("also-not-base64")
                .build()));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::keyPair);

        assertTrue(error.getMessage().contains("push_settings"));
    }

    @Test
    void theSubjectUsesTheAccountRecoveryAddress() {
        when(users.findAll()).thenReturn(List.of(AppUser.builder().email("trader@example.com").build()));

        assertEquals("mailto:trader@example.com", service.subject());
    }

    @Test
    void noUsableAccountAddressIsAnActionableFailure() {
        when(users.findAll()).thenReturn(List.of(
                AppUser.builder().username("one").email(null).build(),
                AppUser.builder().username("two").email(" ").build()));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::subject);

        assertTrue(error.getMessage().contains("account email"));
    }

    @Test
    void noAccountAtAllIsAnActionableFailure() {
        when(users.findAll()).thenReturn(List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class, service::subject);

        assertTrue(error.getMessage().contains("account email"));
        verify(repository, never()).save(any(PushSettings.class));
    }
}
