package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.repository.AppUserRepository;
import nl.jimkaplan.autotrader.service.MailSettingsService;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveAlertServiceTest {

    private static final String EMAIL = "trader@example.com";
    private static final String PUBLIC_URL = "https://example.test/autotrader";

    @Mock
    private MailSettingsService mail;

    @Mock
    private AppUserRepository users;

    @Mock
    private PushNotificationService push;

    private LiveAlertService service;

    @BeforeEach
    void setUp() {
        service = new LiveAlertService(mail, users, push, new SyncTaskExecutor(), "UTC", PUBLIC_URL);
    }

    @Test
    void aRealFillSendsTheOrderToTheAccountEmail() {
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(true);

        service.realFill(strategy(), order("FILLED"), closedPosition());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(EMAIL), contains("REAL BUY filled"), body.capture());
        assertTrue(body.getValue().contains("Strategy: BTC 2h"));
        assertTrue(body.getValue().contains("BTC-EUR 2h, REAL"));
        assertTrue(body.getValue().contains("Quantity: 0,00100000"));
        assertTrue(body.getValue().contains("Price: €73.481,00"));
        assertTrue(body.getValue().contains("Amount: €100,00"));
        assertTrue(body.getValue().contains("Fee: €0,20"));
        assertTrue(body.getValue().contains("Realised: +€1,23"));
        assertTrue(body.getValue().contains("Exchange order: order-1"));
        assertTrue(body.getValue().contains("Client order: client-1"));
        assertTrue(body.getValue().contains(PUBLIC_URL + "/ui/live"));
    }

    @Test
    void aDryRunOrderNeverAlerts() {
        service.realFill(strategy(), order("FILLED", true), closedPosition());

        verifyNoInteractions(mail, users, push);
    }

    @Test
    void aNullOrderDoesNothing() {
        service.realFill(strategy(), null, null);

        verifyNoInteractions(mail, users, push);
    }

    @Test
    void aMinimalOrderStillBuildsAUsefulBody() {
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(true);
        LiveStrategy unnamed = LiveStrategy.builder().market("BTC-EUR").interval("2h").build();
        LiveOrder bare = LiveOrder.builder().build();

        service.realFill(unnamed, bare, null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(EMAIL), contains("REAL order update"), body.capture());
        assertTrue(body.getValue().contains("Strategy: BTC-EUR 2h"));
        assertTrue(body.getValue().contains("BTC-EUR 2h, REAL"));
        assertTrue(body.getValue().contains("Reason: —"));
        assertTrue(body.getValue().contains(PUBLIC_URL + "/ui/live"));
    }

    @Test
    void aFailureBodyCarriesTheError() {
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(true);
        LiveOrder rejected = order("REJECTED");
        rejected.setErrorMessage("Insufficient EUR balance: 1.0");

        service.realFailure(strategy(), rejected, "rejected");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(EMAIL), contains("REAL BUY rejected"), body.capture());
        assertTrue(body.getValue().contains("Error: Insufficient EUR balance: 1.0"));
    }

    @Test
    void theMissingSmtpWarningIsLoggedOnlyOnce() {
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(false);

        service.realFailure(strategy(), order("REJECTED"), "rejected");
        service.realFailure(strategy(), order("REJECTED"), "rejected");

        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void accountEmailSkipsNullAndBlankAddresses() {
        when(users.findAll()).thenReturn(List.of(
                AppUser.builder().username("one").email(null).build(),
                AppUser.builder().username("two").email(" ").build(),
                user()));
        when(mail.configured()).thenReturn(true);

        service.realFill(strategy(), order("FILLED"), null);

        verify(mail).send(eq(EMAIL), anyString(), anyString());
    }

    @Test
    void aRejectedExecutorDoesNotBreakTheTradingPath() {
        LiveAlertService rejecting = new LiveAlertService(mail, users, push,
                command -> {
                    throw new java.util.concurrent.RejectedExecutionException("queue full");
                }, "UTC", PUBLIC_URL);
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(true);

        rejecting.realFill(strategy(), order("FILLED"), null);

        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void aFailingAccountLookupDoesNotEscapeTheTradingPath() {
        when(users.findAll()).thenThrow(new RuntimeException("mongo down"));

        service.realFill(strategy(), order("FILLED"), null);

        verifyNoInteractions(mail);
    }

    @Test
    void sendTestStillThrowsWhenTheAccountLookupFails() {
        when(users.findAll()).thenThrow(new RuntimeException("mongo down"));

        assertThrows(RuntimeException.class, () -> service.sendTest());
    }

    @Test
    void noConfiguredSmtpMeansNoAlertAndNoThrow() {
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(false);

        service.realFailure(strategy(), order("REJECTED"), "rejected");

        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void noAccountEmailMeansNoAlert() {
        when(users.findAll()).thenReturn(List.of());

        service.realPending(strategy(), order("PENDING"));

        verifyNoInteractions(mail);
    }

    @Test
    void aMailFailureIsSwallowedOnTheTradingPath() {
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(true);
        doThrow(new RuntimeException("smtp down")).when(mail)
                .send(anyString(), anyString(), anyString());

        service.realFill(strategy(), order("FILLED"), null);

        verify(mail).send(eq(EMAIL), contains("REAL BUY filled"), anyString());
    }

    @Test
    void theSubjectsNameTheEvent() {
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(true);

        service.realFailure(strategy(), order("REJECTED"), "rejected");
        service.realPending(strategy(), order("PENDING"));
        service.reconciledFill(strategy(), order("FILLED"));
        service.reconciledFailure(strategy(), order("FAILED"));
        service.reconciliationHeldBack(strategy());

        verify(mail).send(eq(EMAIL), contains("REAL BUY rejected"), anyString());
        verify(mail).send(eq(EMAIL), contains("REAL BUY needs reconciliation"), anyString());
        verify(mail).send(eq(EMAIL), contains("REAL BUY reconciled"), anyString());
        verify(mail).send(eq(EMAIL), contains("REAL BUY failed at reconciliation"), anyString());
        verify(mail).send(eq(EMAIL), contains("Live strategy not started"), anyString());
    }

    @Test
    void sendTestNeedsAnEmailAndSmtp() {
        when(users.findAll()).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> service.sendTest());

        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.sendTest());
    }

    @Test
    void sendTestSendsSynchronouslyAndPropagatesAMailFailure() {
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(true);

        service.sendTest();
        verify(mail).send(eq(EMAIL), eq(LiveAlertService.TEST_SUBJECT), contains(EMAIL));

        doThrow(new RuntimeException("smtp down")).when(mail)
                .send(anyString(), anyString(), anyString());
        assertThrows(RuntimeException.class, () -> service.sendTest());
    }

    @Test
    void aRealFillAlsoPushesAShortSummary() {
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(true);

        service.realFill(strategy(), order("FILLED"), closedPosition());

        verify(push).send(contains("REAL BUY filled"), contains("0,00100000 @ €73.481,00"), eq("order-order-1"));
    }

    @Test
    void aPushFailureIsSwallowedOnTheTradingPath() {
        when(users.findAll()).thenReturn(List.of(user()));
        when(mail.configured()).thenReturn(true);
        doThrow(new RuntimeException("push down")).when(push)
                .send(anyString(), anyString(), anyString());

        service.realFill(strategy(), order("FILLED"), null);

        verify(mail).send(eq(EMAIL), contains("REAL BUY filled"), anyString());
    }

    private static AppUser user() {
        return AppUser.builder().username("trader").email(EMAIL).build();
    }

    private static LiveStrategy strategy() {
        LiveStrategy strategy = LiveStrategy.builder()
                .name("BTC 2h").market("BTC-EUR").interval("2h")
                .build();
        strategy.setId("strategy-1");
        return strategy;
    }

    private static LiveOrder order(String status) {
        return order(status, false);
    }

    private static LiveOrder order(String status, boolean dryRun) {
        return LiveOrder.builder()
                .strategyId("strategy-1").market("BTC-EUR").side("BUY").status(status)
                .dryRun(dryRun)
                .quantity(0.001).price(73_481.0).amountQuote(100.0).fee(0.2)
                .orderId("order-1").clientOrderId("client-1")
                .reason("entry ALL [MACD_BULLISH_CROSSOVER]")
                .createdTime(java.time.Instant.parse("2026-10-22T10:00:00Z"))
                .build();
    }

    private static LivePosition closedPosition() {
        return LivePosition.builder()
                .strategyId("strategy-1").market("BTC-EUR").status("CLOSED")
                .quantity(0.001).entryPrice(73_000).entryFee(0.2)
                .exitPrice(73_481.0).profit(1.23)
                .build();
    }
}
