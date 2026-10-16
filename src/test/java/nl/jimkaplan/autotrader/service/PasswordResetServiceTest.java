package nl.jimkaplan.autotrader.service;

import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.model.document.PasswordResetToken;
import nl.jimkaplan.autotrader.repository.AppUserRepository;
import nl.jimkaplan.autotrader.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mail.MailSendException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Recovery by mail: the one answer, the hashed single-use token with its expiry, and the link the
 * mail carries. The account repository, the token store and the mailer are stubbed; time is fixed.
 */
class PasswordResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
    private final AppUserService appUsers = mock(AppUserService.class);
    private final MailSettingsService mail = mock(MailSettingsService.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private PasswordResetService service;
    private PasswordResetToken stored;

    @BeforeEach
    void wireTheTokenStore() {
        service = new PasswordResetService(users, tokens, appUsers, mail, mongoTemplate,
                Runnable::run, "https://at.bluris.nl/autotrader", 30, clock);
        when(tokens.save(any())).thenAnswer(invocation -> {
            stored = invocation.getArgument(0);
            return stored;
        });
        when(tokens.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored).filter(token -> token.getId().equals(invocation.getArgument(0))));
        when(tokens.findByTokenHash(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored)
                        .filter(token -> token.getTokenHash().equals(invocation.getArgument(0))));
        // The two conditional writes the service makes are mirrored on the in-memory document: the
        // claim spends a live token, and the restore puts an earlier one back — but only while the
        // stored token is still the one the request minted.
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(PasswordResetToken.class)))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    Update update = invocation.getArgument(1);
                    if (query.getQueryObject().containsKey("_id")) {
                        if (stored == null || !stored.getTokenHash()
                                .equals(query.getQueryObject().getString("token_hash"))) {
                            return UpdateResult.acknowledged(0, 0L, null);
                        }
                        Document changes = update.getUpdateObject().get("$set", Document.class);
                        stored.setTokenHash(changes.getString("token_hash"));
                        stored.setCreatedTime(instantOf(changes.get("created_time")));
                        stored.setExpiresTime(instantOf(changes.get("expires_time")));
                        return UpdateResult.acknowledged(1, 1L, null);
                    }
                    if (stored == null || stored.getUsedTime() != null) {
                        return UpdateResult.acknowledged(0, 0L, null);
                    }
                    stored.setUsedTime(NOW);
                    return UpdateResult.acknowledged(1, 1L, null);
                });
        when(mongoTemplate.remove(any(Query.class), eq(PasswordResetToken.class)))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    if (stored != null && stored.getTokenHash()
                            .equals(query.getQueryObject().getString("token_hash"))) {
                        stored = null;
                    }
                    return DeleteResult.acknowledged(0);
                });
    }

    @Test
    void anUnknownAddressGetsTheSameAnswerAndNothingElse() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertEquals(PasswordResetService.SENT_MESSAGE, service.request("nobody@example.com"));
        verifyNoInteractions(tokens, mail);
    }

    @Test
    void aKnownAddressGetsALinkWhoseTokenIsOnlyStoredHashed() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        assertEquals(PasswordResetService.SENT_MESSAGE, service.request(" Jim@Example.com "));

        verify(mail).send(eq("jim@example.com"), contains("password"), body.capture());
        String token = tokenOf(body.getValue());
        assertNotEquals(token, stored.getTokenHash());
        assertEquals(64, stored.getTokenHash().length());
        assertEquals("u1", stored.getId());
        assertEquals(NOW.plus(Duration.ofMinutes(30)), stored.getExpiresTime());
        assertTrue(service.valid(token));
    }

    @Test
    void aRequestInsideTheCooldownKeepsTheLinkOnItsWay() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        service.request("jim@example.com");
        verify(mail).send(any(), any(), body.capture());
        String first = tokenOf(body.getValue());

        // A double-click, or an impatient second try, must not send another mail and must not
        // invalidate the link that is already on its way.
        assertEquals(PasswordResetService.SENT_MESSAGE, service.request("jim@example.com"));

        verify(mail, org.mockito.Mockito.times(1)).send(any(), any(), any());
        assertTrue(service.valid(first));
    }

    @Test
    void aRequestAfterTheCooldownMintsAFreshLink() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        service.request("jim@example.com");
        verify(mail).send(any(), any(), body.capture());
        String first = tokenOf(body.getValue());

        // Three minutes later the cooldown has passed: a new link goes out and replaces the old one.
        PasswordResetService later = new PasswordResetService(users, tokens, appUsers, mail,
                mongoTemplate, Runnable::run, "https://at.bluris.nl/autotrader", 30,
                Clock.fixed(NOW.plus(Duration.ofMinutes(3)), ZoneOffset.UTC));
        ArgumentCaptor<String> secondBody = ArgumentCaptor.forClass(String.class);
        later.request("jim@example.com");

        verify(mail, org.mockito.Mockito.times(2)).send(any(), any(), secondBody.capture());
        String second = tokenOf(secondBody.getValue());
        assertNotEquals(first, second);
        assertFalse(service.valid(first));
        assertTrue(later.valid(second));
    }

    @Test
    void theCooldownSeesTheLinkBeforeTheMailIsOnTheWire() {
        // The worker has not run at all: the token is minted and stored on the request thread, so
        // the very next request already sees a link on its way and sends nothing.
        List<Runnable> queued = new ArrayList<>();
        PasswordResetService deferred = new PasswordResetService(users, tokens, appUsers, mail,
                mongoTemplate, queued::add, "https://at.bluris.nl/autotrader", 30, clock);
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(account()));

        deferred.request("jim@example.com");
        deferred.request("jim@example.com");

        assertEquals(1, queued.size());
        assertNotNull(stored);
        verify(mail, never()).send(any(), any(), any());
    }

    @Test
    void aFullWorkerQueueStillAnswersTheSameWay() {
        // An overloaded worker must not turn into an oracle: the account's address answers exactly
        // like any other.
        PasswordResetService refusing = new PasswordResetService(users, tokens, appUsers, mail,
                mongoTemplate, command -> {
                    throw new RejectedExecutionException("queue full");
                }, "https://at.bluris.nl/autotrader", 30, clock);
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(account()));

        assertEquals(PasswordResetService.SENT_MESSAGE, refusing.request("jim@example.com"));
        verify(mail, never()).send(any(), any(), any());
    }

    @Test
    void aRefusedSubmissionLeavesThePreviousLinkAlive() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        service.request("jim@example.com");
        verify(mail).send(any(), any(), body.capture());
        String first = tokenOf(body.getValue());

        // Three minutes later the cooldown has passed, but the worker refuses the new send: the link
        // already in the inbox must keep working, and the unsent token must not replace it.
        PasswordResetService refusing = new PasswordResetService(users, tokens, appUsers, mail,
                mongoTemplate, command -> {
                    throw new RejectedExecutionException("queue full");
                }, "https://at.bluris.nl/autotrader", 30,
                Clock.fixed(NOW.plus(Duration.ofMinutes(3)), ZoneOffset.UTC));

        assertEquals(PasswordResetService.SENT_MESSAGE, refusing.request("jim@example.com"));

        assertTrue(service.valid(first));
        verify(mail, org.mockito.Mockito.times(1)).send(any(), any(), any());
    }

    @Test
    void aFailedSendKeepsThePreviousLinkAlive() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        service.request("jim@example.com");
        verify(mail).send(any(), any(), body.capture());
        String first = tokenOf(body.getValue());

        // The mail server is down for the next request: the link that already arrived stays live.
        doThrow(new MailSendException("smtp down")).when(mail).send(any(), any(), any());
        PasswordResetService failing = new PasswordResetService(users, tokens, appUsers, mail,
                mongoTemplate, Runnable::run, "https://at.bluris.nl/autotrader", 30,
                Clock.fixed(NOW.plus(Duration.ofMinutes(3)), ZoneOffset.UTC));
        failing.request("jim@example.com");

        assertTrue(service.valid(first));
    }

    @Test
    void theRestoreLeavesATokenAnotherRequestMintedAlone() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        service.request("jim@example.com");
        verify(mail).send(any(), any(), body.capture());
        String first = tokenOf(body.getValue());

        // The submission is refused while, in the same window, another request has already minted
        // its own token: the restore must not take that one away.
        PasswordResetToken anotherRequest = PasswordResetToken.builder().id("u1")
                .tokenHash("another-request-minted-this").createdTime(NOW)
                .expiresTime(NOW.plus(Duration.ofMinutes(30))).build();
        PasswordResetService refusing = new PasswordResetService(users, tokens, appUsers, mail,
                mongoTemplate, command -> {
                    stored = anotherRequest;
                    throw new RejectedExecutionException("queue full");
                }, "https://at.bluris.nl/autotrader", 30,
                Clock.fixed(NOW.plus(Duration.ofMinutes(3)), ZoneOffset.UTC));

        refusing.request("jim@example.com");

        assertEquals("another-request-minted-this", stored.getTokenHash());
        assertFalse(service.valid(first));
    }

    @Test
    void aUsedTokenIsSpent() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        when(users.findById("u1")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        service.request("jim@example.com");

        verify(mail).send(any(), any(), body.capture());
        String token = tokenOf(body.getValue());
        service.reset(token, "new-password-1", "new-password-1");

        verify(appUsers).setPassword(user, "new-password-1", "new-password-1");
        assertFalse(service.valid(token));
        assertThrows(IllegalArgumentException.class,
                () -> service.reset(token, "another-password", "another-password"));
    }

    @Test
    void aTokenAnotherRequestClaimedFirstIsRefused() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        when(users.findById("u1")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        service.request("jim@example.com");
        verify(mail).send(any(), any(), body.capture());
        String token = tokenOf(body.getValue());
        // The claim finds nothing to take: someone else spent it between the read and the write.
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(PasswordResetToken.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThrows(IllegalArgumentException.class,
                () -> service.reset(token, "new-password-1", "new-password-1"));
    }

    @Test
    void aBrokenPasswordDoesNotSpendTheLink() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        when(users.findById("u1")).thenReturn(Optional.of(user));
        doThrow(new IllegalArgumentException("The password must be at least 10 characters."))
                .when(appUsers).validatePassword("short", "short");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        service.request("jim@example.com");
        verify(mail).send(any(), any(), body.capture());
        String token = tokenOf(body.getValue());

        assertThrows(IllegalArgumentException.class, () -> service.reset(token, "short", "short"));
        assertTrue(service.valid(token));
        verify(appUsers, org.mockito.Mockito.never()).setPassword(any(), any(), any());
    }

    @Test
    void anExpiredTokenIsRefused() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        service.request("jim@example.com");
        verify(mail).send(any(), any(), body.capture());
        String token = tokenOf(body.getValue());

        PasswordResetService later = new PasswordResetService(users, tokens, appUsers, mail,
                mongoTemplate, Runnable::run, "https://at.bluris.nl/autotrader", 30,
                Clock.fixed(NOW.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));

        assertFalse(later.valid(token));
        assertThrows(IllegalArgumentException.class,
                () -> later.reset(token, "new-password-1", "new-password-1"));
    }

    @Test
    void aMailServerFailureIsLoggedAndStillTheSameAnswer() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        doThrow(new MailSendException("smtp down")).when(mail).send(any(), any(), any());

        assertEquals(PasswordResetService.SENT_MESSAGE, service.request("jim@example.com"));
    }

    @Test
    void aTokenWhoseAccountIsGoneIsRefused() {
        AppUser user = account();
        when(users.findByEmail("jim@example.com")).thenReturn(Optional.of(user));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        service.request("jim@example.com");
        verify(mail).send(any(), any(), body.capture());
        String token = tokenOf(body.getValue());
        when(users.findById("u1")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.reset(token, "new-password-1", "new-password-1"));

        assertTrue(error.getMessage().contains("no longer valid"));
    }

    @Test
    void resetRefusesAnUnknownToken() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.reset("nope", "long-password", "long-password"));

        assertTrue(error.getMessage().contains("no longer valid"));
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        return value instanceof java.util.Date date ? date.toInstant() : null;
    }

    private static AppUser account() {
        return AppUser.builder().id("u1").username("jim").email("jim@example.com").build();
    }

    private static String tokenOf(String mailBody) {
        int start = mailBody.indexOf("token=") + "token=".length();
        assertTrue(start > 0, "the mail should carry a link with a token: " + mailBody);
        String rest = mailBody.substring(start);
        int end = 0;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) {
            end++;
        }
        return rest.substring(0, end);
    }
}
