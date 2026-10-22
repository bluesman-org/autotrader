package nl.jimkaplan.autotrader.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The single thread the recovery mail leaves on, and the one the live engine's alerts leave on, so
 * the request that asked for a mail answers at once and a slow SMTP server cannot hold a request
 * thread or a trading decision.
 *
 * <p>Each queue is small and bounded: a stuck SMTP server holds one send while a handful of others
 * wait, and beyond that the submission is refused (which the caller logs) rather than growing a
 * queue of work nobody asked for. The threads are daemons, so a mail in flight never keeps the
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
