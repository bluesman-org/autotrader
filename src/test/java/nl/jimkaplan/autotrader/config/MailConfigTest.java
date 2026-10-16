package nl.jimkaplan.autotrader.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recovery mail leaves on a thread of its own — one, daemon, named — so a slow SMTP server
 * cannot hold a request thread or keep the application from shutting down.
 */
class MailConfigTest {

    @Test
    void theRecoveryMailRunsOnItsOwnDaemonThread() throws Exception {
        Executor executor = new MailConfig().recoveryMailExecutor();
        try {
            String name = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), executor)
                    .get(5, TimeUnit.SECONDS);
            boolean daemon = CompletableFuture.supplyAsync(Thread::currentThread, executor)
                    .get(5, TimeUnit.SECONDS).isDaemon();

            assertEquals("recovery-mail", name);
            assertTrue(daemon);
        } finally {
            ((ExecutorService) executor).shutdownNow();
        }
    }
}
