package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.repository.PushSubscriptionRepository;
import nl.jimkaplan.autotrader.strategy.controller.PushSubscriptionRequest;
import nl.jimkaplan.autotrader.strategy.model.document.PushSubscriptionDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTest {

    private static final String ENDPOINT = "https://push.example.com/abc";

    @Mock
    private PushSubscriptionRepository repository;

    @InjectMocks
    private PushSubscriptionService service;

    @Test
    void aNewSubscriptionIsStoredWithItsBrowserAndTime() {
        service.subscribe(request(" p256dh ", " auth "), "Mozilla/5.0");

        ArgumentCaptor<PushSubscriptionDocument> captor =
                ArgumentCaptor.forClass(PushSubscriptionDocument.class);
        verify(repository).save(captor.capture());
        PushSubscriptionDocument stored = captor.getValue();
        assertEquals(ENDPOINT, stored.getEndpoint());
        assertEquals("p256dh", stored.getP256dh());
        assertEquals("auth", stored.getAuth());
        assertEquals("Mozilla/5.0", stored.getUserAgent());
        assertNotNull(stored.getCreatedTime());
    }

    @Test
    void subscribingAgainUpdatesTheKeysOnTheSameDocument() {
        PushSubscriptionDocument existing = document();
        Instant created = existing.getCreatedTime();
        when(repository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));

        service.subscribe(request("new-key", "new-auth"), "Other browser");

        assertEquals("new-key", existing.getP256dh());
        assertEquals("new-auth", existing.getAuth());
        assertEquals("Other browser", existing.getUserAgent());
        assertEquals(created, existing.getCreatedTime());
        verify(repository).save(existing);
    }

    @Test
    void aLongUserAgentIsTruncated() {
        String agent = "a".repeat(300);

        service.subscribe(request("p256dh", "auth"), agent);

        ArgumentCaptor<PushSubscriptionDocument> captor =
                ArgumentCaptor.forClass(PushSubscriptionDocument.class);
        verify(repository).save(captor.capture());
        assertEquals(255, captor.getValue().getUserAgent().length());
    }

    @Test
    void incompleteOrUnsafeSubscriptionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.subscribe(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.subscribe(new PushSubscriptionRequest(ENDPOINT, null), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.subscribe(new PushSubscriptionRequest(ENDPOINT,
                        new PushSubscriptionRequest.Keys(null, "auth")), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.subscribe(new PushSubscriptionRequest(ENDPOINT,
                        new PushSubscriptionRequest.Keys("p256dh", " ")), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.subscribe(new PushSubscriptionRequest(" ", keys()), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.subscribe(new PushSubscriptionRequest("http://push.example.com/abc", keys()), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.subscribe(new PushSubscriptionRequest("not a url", keys()), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.subscribe(new PushSubscriptionRequest("https://push.example.com/" + "a".repeat(2048),
                        keys()), null));
        verify(repository, never()).save(any());
    }

    @Test
    void unsubscribeRemovesTheEndpoint() {
        service.unsubscribe(ENDPOINT);

        verify(repository).deleteByEndpoint(ENDPOINT);
    }

    @Test
    void unsubscribeRejectsAnInvalidEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> service.unsubscribe(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.unsubscribe("http://push.example.com/abc"));
        verify(repository, never()).deleteByEndpoint(any());
    }

    @Test
    void countAndAllReadTheRepository() {
        when(repository.count()).thenReturn(3L);
        when(repository.findAll()).thenReturn(List.of(document()));

        assertEquals(3L, service.count());
        assertEquals(1, service.all().size());
    }

    @Test
    void successAndFailureAreRecordedOnTheSubscription() {
        PushSubscriptionDocument document = document();
        when(repository.findById("subscription-1")).thenReturn(Optional.of(document));

        service.markSuccess("subscription-1");
        assertNotNull(document.getLastSuccessTime());

        service.markFailure("subscription-1");
        assertNotNull(document.getLastFailureTime());
        verify(repository, org.mockito.Mockito.times(2)).save(document);
    }

    @Test
    void aGoneSubscriptionIsDeletedAndNullIdsAreIgnored() {
        service.delete("subscription-1");
        service.markSuccess(null);
        service.markFailure(null);
        service.delete(null);

        verify(repository).deleteById("subscription-1");
        verify(repository, never()).findById(any());
    }

    private static PushSubscriptionRequest request(String p256dh, String auth) {
        return new PushSubscriptionRequest(ENDPOINT, new PushSubscriptionRequest.Keys(p256dh, auth));
    }

    private static PushSubscriptionRequest.Keys keys() {
        return new PushSubscriptionRequest.Keys("p256dh", "auth");
    }

    private static PushSubscriptionDocument document() {
        PushSubscriptionDocument document = PushSubscriptionDocument.builder()
                .endpoint(ENDPOINT)
                .p256dh("old-key")
                .auth("old-auth")
                .createdTime(Instant.parse("2026-10-20T10:00:00Z"))
                .build();
        document.setId("subscription-1");
        return document;
    }
}
