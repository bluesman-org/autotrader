package nl.jimkaplan.autotrader.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The single thread the recovery mail leaves on, the one the live engine's email alerts leave on,
 * and the one its Web Push notifications leave on, so the request that asked for a mail answers at
 * once and a slow SMTP or push service cannot hold a request thread or a trading decision.
 *
 * <p>Each queue is small and bounded: a stuck server holds one send while a handful of others
 * wait, and beyond that the submission is refused (which the caller logs) rather than growing a
 * queue of work nobody asked for. The threads are daemons, so a send in flight never keeps the
 * application from shutting down.</p>
 */
@Configuration
public class MailConfig {

    /**
     * How many sends may wait behind the one on the wire.
     */
    static final int QUEUE_CAPACITY = 8;

    /**
     * The live alerts are a little burstier than recovery mail: a fill and its failure can arrive
     * together, and a few strategies may alert at once.
     */
    static final int ALERT_QUEUE_CAPACITY = 16;

    @Bean(destroyMethod = "shutdown")
    public Executor recoveryMailExecutor() {
        return singleThreadExecutor("recovery-mail", QUEUE_CAPACITY);
    }

    @Bean(destroyMethod = "shutdown")
    public Executor liveAlertExecutor() {
        return singleThreadExecutor("live-alert-mail", ALERT_QUEUE_CAPACITY);
    }

    /**
     * The push sends are normally as rare as the email alerts, but one event fans out over every
     * subscribed browser, so they get their own thread: a slow push service must not delay the
     * email that says the same thing.
     */
    @Bean(destroyMethod = "shutdown")
    public Executor livePushExecutor() {
        return singleThreadExecutor("live-alert-push", ALERT_QUEUE_CAPACITY);
    }

    private static Executor singleThreadExecutor(String name, int queueCapacity) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
