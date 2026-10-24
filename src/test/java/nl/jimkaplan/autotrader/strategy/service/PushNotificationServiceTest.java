package nl.jimkaplan.autotrader.strategy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zerodeplibs.webpush.VAPIDKeyPair;
import com.zerodeplibs.webpush.VAPIDKeyPairs;
import com.zerodeplibs.webpush.key.PrivateKeySources;
import com.zerodeplibs.webpush.key.PublicKeySources;
import nl.jimkaplan.autotrader.strategy.model.document.PushSubscriptionDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock
    private PushSettingsService settings;

    @Mock
    private PushSubscriptionService subscriptions;

    @Mock
    private PushSender sender;

    private VAPIDKeyPair keyPair;
    private PushNotificationService service;

    @BeforeEach
    void setUp() {
        keyPair = keyPair();
        service = new PushNotificationService(settings, subscriptions, sender, new ObjectMapper(),
                Runnable::run, "https://example.test/autotrader/");
    }

    @Test
    void aNotificationIsSignedEncryptedAndSentToEverySubscription() throws Exception {
        when(subscriptions.all()).thenReturn(List.of(document()));
        when(settings.keyPair()).thenReturn(keyPair);
        when(settings.subject()).thenReturn("mailto:trader@example.com");
        when(sender.send(any(HttpRequest.class))).thenReturn(201);

        service.send("REAL BUY filled · BTC-EUR", "0,001 @ €73.481,00", "order-1");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(sender).send(captor.capture());
        HttpRequest request = captor.getValue();
        assertEquals(URI.create("https://push.example.com/abc"), request.uri());
        assertTrue(request.headers().firstValue("Authorization").orElse("").startsWith("vapid t="));
        assertEquals("3600", request.headers().firstValue("TTL").orElse(null));
        assertEquals("high", request.headers().firstValue("Urgency").orElse(null));
        assertTrue(request.bodyPublisher().isPresent());
        verify(subscriptions).markSuccess("subscription-1");
        verify(subscriptions, never()).delete("subscription-1");
    }

    @Test
    void aGoneSubscriptionIsRemovedAndARejectedOneIsKept() throws Exception {
        when(subscriptions.all()).thenReturn(List.of(document()));
        when(settings.keyPair()).thenReturn(keyPair);
        when(settings.subject()).thenReturn("mailto:trader@example.com");
        when(sender.send(any(HttpRequest.class))).thenReturn(410);

        service.send("REAL BUY filled", "body", "order-1");

        verify(subscriptions).delete("subscription-1");
        verify(subscriptions, never()).markSuccess("subscription-1");
    }

    @Test
    void aServerErrorIsRecordedAndTheSubscriptionIsKept() throws Exception {
        when(subscriptions.all()).thenReturn(List.of(document()));
        when(settings.keyPair()).thenReturn(keyPair);
        when(settings.subject()).thenReturn("mailto:trader@example.com");
        when(sender.send(any(HttpRequest.class))).thenReturn(503);

        service.send("REAL BUY filled", "body", "order-1");

        verify(subscriptions).markFailure("subscription-1");
        verify(subscriptions, never()).delete("subscription-1");
    }

    @Test
    void aTransportFailureIsSwallowedAndRecorded() throws Exception {
        when(subscriptions.all()).thenReturn(List.of(document()));
        when(settings.keyPair()).thenReturn(keyPair);
        when(settings.subject()).thenReturn("mailto:trader@example.com");
        when(sender.send(any(HttpRequest.class))).thenThrow(new IOException("push service down"));

        assertDoesNotThrow(() -> service.send("REAL BUY filled", "body", "order-1"));

        verify(subscriptions).markFailure("subscription-1");
    }

    @Test
    void anInterruptedSendRestoresTheInterruptFlag() throws Exception {
        when(subscriptions.all()).thenReturn(List.of(document()));
        when(settings.keyPair()).thenReturn(keyPair);
        when(settings.subject()).thenReturn("mailto:trader@example.com");
        when(sender.send(any(HttpRequest.class))).thenThrow(new InterruptedException("stop"));

        service.send("REAL BUY filled", "body", "order-1");

        verify(subscriptions).markFailure("subscription-1");
        assertTrue(Thread.interrupted());
    }

    @Test
    void noSubscriptionMeansNoSendAtAll() {
        when(subscriptions.all()).thenReturn(List.of());

        service.send("REAL BUY filled", "body", "order-1");

        verifyNoInteractions(sender, settings);
        verify(subscriptions, never()).markSuccess(any());
    }

    @Test
    void aRejectedExecutorDoesNotEscapeTheTradingPath() {
        PushNotificationService rejecting = new PushNotificationService(settings, subscriptions, sender,
                new ObjectMapper(), command -> {
            throw new RejectedExecutionException("queue full");
        }, "https://example.test/autotrader");

        assertDoesNotThrow(() -> rejecting.send("REAL BUY filled", "body", "order-1"));

        verifyNoInteractions(sender, subscriptions);
    }

    @Test
    void aBrokenKeyPairIsSwallowedForANormalNotification() {
        when(subscriptions.all()).thenReturn(List.of(document()));
        when(settings.keyPair()).thenThrow(new IllegalStateException("bad key"));

        assertDoesNotThrow(() -> service.send("REAL BUY filled", "body", "order-1"));

        verifyNoInteractions(sender);
    }

    @Test
    void nullFieldsFallBackToSafePayloadDefaults() throws Exception {
        when(subscriptions.all()).thenReturn(List.of(document()));
        when(settings.keyPair()).thenReturn(keyPair);
        when(settings.subject()).thenReturn("mailto:trader@example.com");
        when(sender.send(any(HttpRequest.class))).thenReturn(201);

        assertDoesNotThrow(() -> service.send(null, null, null));

        verify(sender).send(any(HttpRequest.class));
    }

    @Test
    void sendTestNeedsASubscription() {
        when(subscriptions.all()).thenReturn(List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.sendTest());

        assertTrue(error.getMessage().contains("No push subscription"));
    }

    @Test
    void sendTestReportsHowManyDevicesAccepted() throws Exception {
        when(subscriptions.all()).thenReturn(List.of(document()));
        when(settings.keyPair()).thenReturn(keyPair);
        when(settings.subject()).thenReturn("mailto:trader@example.com");
        when(sender.send(any(HttpRequest.class))).thenReturn(202);

        assertEquals(1, service.sendTest());

        verify(subscriptions).markSuccess("subscription-1");
    }

    @Test
    void sendTestFailsWhenEveryPushServiceRejectsIt() throws Exception {
        when(subscriptions.all()).thenReturn(List.of(document()));
        when(settings.keyPair()).thenReturn(keyPair);
        when(settings.subject()).thenReturn("mailto:trader@example.com");
        when(sender.send(any(HttpRequest.class))).thenReturn(410);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.sendTest());

        assertTrue(error.getMessage().contains("accepted no notification"));
    }

    @Test
    void aMalformedStoredEndpointIsHandledPerSubscription() {
        PushSubscriptionDocument broken = document();
        broken.setEndpoint("not a url");
        when(subscriptions.all()).thenReturn(List.of(broken));
        when(settings.keyPair()).thenReturn(keyPair);
        when(settings.subject()).thenReturn("mailto:trader@example.com");

        assertDoesNotThrow(() -> service.send("REAL BUY filled", "body", "order-1"));

        verify(subscriptions).markFailure("subscription-1");
        verifyNoInteractions(sender);
    }

    private PushSubscriptionDocument document() {
        PushSubscriptionDocument document = PushSubscriptionDocument.builder()
                .endpoint("https://push.example.com/abc")
                .p256dh(Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(keyPair.extractPublicKeyInUncompressedForm()))
                .auth(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]))
                .build();
        document.setId("subscription-1");
        return document;
    }

    private static VAPIDKeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            return VAPIDKeyPairs.of(
                    PrivateKeySources.ofECPrivateKey((ECPrivateKey) pair.getPrivate()),
                    PublicKeySources.ofECPublicKey((ECPublicKey) pair.getPublic()));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
