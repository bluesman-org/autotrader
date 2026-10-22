package nl.jimkaplan.autotrader.strategy.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.repository.AppUserRepository;
import nl.jimkaplan.autotrader.service.MailSettingsService;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends the operator's email alerts for the live engine: real fills, real failures and orders that
 * are left awaiting reconciliation.
 *
 * <p>Mail is sent off the trading thread and a mail failure is swallowed after a log line, so a
 * broken SMTP server can never delay a decision or lose an order. The recipient is the account's
 * recovery address; there is one account per installation. Dry-run activity never alerts.</p>
 */
@Slf4j
@Service
public class LiveAlertService {

    static final String TEST_SUBJECT = "[autotrader] Test alert";

    private static final String DASH = "—";

    private final MailSettingsService mailSettingsService;
    private final AppUserRepository appUserRepository;
    private final Executor taskExecutor;
    private final DisplayZone display;
    private final String publicUrl;
    private final AtomicBoolean missingMailWarned = new AtomicBoolean();

    public LiveAlertService(MailSettingsService mailSettingsService,
                            AppUserRepository appUserRepository,
                            @Qualifier("liveAlertExecutor") Executor taskExecutor,
                            @Value("${autotrader.ui.time-zone:}") String timeZone,
                            @Value("${autotrader.security.public-url:http://localhost:8081/autotrader}")
                            String publicUrl) {
        this.mailSettingsService = mailSettingsService;
        this.appUserRepository = appUserRepository;
        this.taskExecutor = taskExecutor;
        this.display = new DisplayZone(timeZone);
        this.publicUrl = publicUrl;
    }

    /**
     * Alert on a real fill. The position is the one that opened or closed, when the caller has it;
     * its realised profit is included for a close.
     *
     * @param strategy The strategy
     * @param order    The filled order
     * @param position The position opened or closed, or null when it is not available
     */
    public void realFill(LiveStrategy strategy, LiveOrder order, LivePosition position) {
        if (order == null || order.isDryRun()) {
            return;
        }
        send(subject("REAL " + side(order) + " " + status(order), strategy),
                body(strategy, order, position));
    }

    /**
     * Alert on a real order that did not fill: a local rejection, an exchange failure, or an order
     * the engine had to leave for reconciliation.
     *
     * @param strategy The strategy
     * @param order    The order
     * @param kind     A short description, such as {@code rejected} or {@code failed}
     */
    public void realFailure(LiveStrategy strategy, LiveOrder order, String kind) {
        if (order == null || order.isDryRun()) {
            return;
        }
        send(subject("REAL " + side(order) + " " + kind, strategy), body(strategy, order, null));
    }

    /**
     * Alert that a real order is not settled and the strategy is standing down until the next boot
     * reconciles it.
     *
     * @param strategy The strategy
     * @param order    The pending order
     */
    public void realPending(LiveStrategy strategy, LiveOrder order) {
        if (order == null || order.isDryRun()) {
            return;
        }
        send(subject("REAL " + side(order) + " needs reconciliation", strategy),
                body(strategy, order, null));
    }

    /**
     * Alert that boot reconciliation settled an order as filled.
     *
     * @param strategy The strategy
     * @param order    The reconciled order
     */
    public void reconciledFill(LiveStrategy strategy, LiveOrder order) {
        if (order == null || order.isDryRun()) {
            return;
        }
        send(subject("REAL " + side(order) + " reconciled", strategy), body(strategy, order, null));
    }

    /**
     * Alert that boot reconciliation settled an order as failed or never accepted.
     *
     * @param strategy The strategy
     * @param order    The reconciled order
     */
    public void reconciledFailure(LiveStrategy strategy, LiveOrder order) {
        if (order == null || order.isDryRun()) {
            return;
        }
        send(subject("REAL " + side(order) + " failed at reconciliation", strategy),
                body(strategy, order, null));
    }

    /**
     * Alert that a strategy was held back because its reconciliation failed.
     *
     * @param strategy The strategy that was not started
     */
    public void reconciliationHeldBack(LiveStrategy strategy) {
        String name = name(strategy);
        String body = "Strategy: " + name + " (" + strategy.getMarket() + " " + strategy.getInterval()
                + ")\n"
                + "The strategy was not started because reconciliation with the exchange failed. "
                + "Check the application log, fix the exchange, key or network problem, and restart "
                + "the application.\n\n"
                + livePageLine() + "\n";
        send(subject("Live strategy not started · " + strategy.getMarket(), strategy), body);
    }

    /**
     * Send a test alert synchronously, so the settings page can report a wrong SMTP server or a
     * missing account address immediately. The normal alerts are asynchronous.
     *
     * @throws IllegalStateException when there is no account email or no SMTP server
     */
    public void sendTest() {
        String recipient = accountEmail().orElseThrow(() -> new IllegalStateException(
                "No account email is set; add one before using alerts."));
        if (!mailSettingsService.configured()) {
            throw new IllegalStateException(
                    "No SMTP server is configured yet; add one on the settings page.");
        }
        String body = "This is a test alert from Autotrader.\n"
                + "Alerts are sent to " + recipient + ".\n"
                + "Real fills, real failures and orders awaiting reconciliation alert you here.\n\n"
                + livePageLine() + "\n";
        mailSettingsService.send(recipient, TEST_SUBJECT, body);
    }

    private void send(String subject, String body) {
        Optional<String> recipient = accountEmail();
        if (recipient.isEmpty()) {
            log.warn("No account email is set, so the live alert was not sent: {}", subject);
            return;
        }
        if (!mailSettingsService.configured()) {
            if (missingMailWarned.compareAndSet(false, true)) {
                log.warn("No SMTP server is configured, so live alerts are not being sent; add one "
                        + "on the settings page");
            }
            return;
        }
        String to = recipient.get();
        try {
            taskExecutor.execute(() -> {
                try {
                    mailSettingsService.send(to, subject, body);
                } catch (RuntimeException ex) {
                    log.warn("Could not send the live alert '{}': {}", subject, ex.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            log.warn("Could not schedule the live alert '{}': {}", subject, ex.getMessage());
        }
    }

    private Optional<String> accountEmail() {
        List<AppUser> users = appUserRepository.findAll();
        return users.stream()
                .map(AppUser::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .findFirst();
    }

    private String body(LiveStrategy strategy, LiveOrder order, LivePosition position) {
        StringBuilder body = new StringBuilder();
        body.append("Strategy: ").append(name(strategy)).append(" (")
                .append(strategy.getMarket()).append(' ').append(strategy.getInterval())
                .append(", REAL)\n");
        body.append("Side: ").append(order.getSide()).append('\n');
        body.append("Status: ").append(order.getStatus()).append('\n');
        if (order.getQuantity() != null) {
            body.append("Quantity: ").append(ViewText.decimal(order.getQuantity(), 8)).append('\n');
        }
        if (order.getPrice() != null) {
            body.append("Price: ").append(ViewText.euro(order.getPrice())).append('\n');
        }
        if (order.getAmountQuote() != null) {
            body.append("Amount: ").append(ViewText.euro(order.getAmountQuote())).append('\n');
        }
        if (order.getFee() != null) {
            body.append("Fee: ").append(ViewText.euro(order.getFee())).append('\n');
        }
        if (position != null && position.getProfit() != null) {
            body.append("Realised: ").append(ViewText.signed(position.getProfit())).append('\n');
        }
        if (order.getOrderId() != null) {
            body.append("Exchange order: ").append(order.getOrderId()).append('\n');
        }
        if (order.getClientOrderId() != null) {
            body.append("Client order: ").append(order.getClientOrderId()).append('\n');
        }
        if (order.getErrorMessage() != null) {
            body.append("Error: ").append(order.getErrorMessage()).append('\n');
        }
        body.append("Time: ").append(display.minuteYear(Instant.now()))
                .append(" (").append(display.label()).append(")\n");
        body.append("Reason: ").append(order.getReason() == null ? DASH : order.getReason()).append('\n');
        body.append('\n').append(livePageLine()).append('\n');
        return body.toString();
    }

    private static String subject(String what, LiveStrategy strategy) {
        return "[autotrader] " + what + " · " + strategy.getMarket();
    }

    private static String side(LiveOrder order) {
        String side = order.getSide();
        return side == null ? "order" : side.toUpperCase(Locale.ROOT);
    }

    private static String status(LiveOrder order) {
        String status = order.getStatus();
        return status == null ? "update" : status.toLowerCase(Locale.ROOT);
    }

    private static String name(LiveStrategy strategy) {
        return strategy.getName() == null || strategy.getName().isBlank()
                ? strategy.getMarket() + " " + strategy.getInterval()
                : strategy.getName();
    }

    private String livePageLine() {
        return "Open the live page: " + publicUrl + "/ui/live";
    }
}
