package nl.jimkaplan.autotrader.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The single thread the recovery mail leaves on, so the request that asked for it answers at once —
 * an address that is the account's must not be distinguishable from one that is not by how long the
 * page takes.
 *
 * <p>The queue is small and bounded: a stuck SMTP server holds one send while a handful of others
 * wait, and beyond that the submission is refused (which the caller answers like any other request)
 * rather than growing a queue of work nobody asked for. The thread is a daemon, so a mail in flight
 * never keeps the application from shutting down.</p>
 */
@Configuration
public class MailConfig {

    /**
     * How many sends may wait behind the one on the wire.
     */
    static final int QUEUE_CAPACITY = 8;

    @Bean(destroyMethod = "shutdown")
    public Executor recoveryMailExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "recovery-mail");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
