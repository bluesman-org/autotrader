package nl.jimkaplan.autotrader.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void theLiveAlertRunsOnItsOwnDaemonThread() throws Exception {
        Executor executor = new MailConfig().liveAlertExecutor();
        try {
            String name = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), executor)
                    .get(5, TimeUnit.SECONDS);
            boolean daemon = CompletableFuture.supplyAsync(Thread::currentThread, executor)
                    .get(5, TimeUnit.SECONDS).isDaemon();

            assertEquals("live-alert-mail", name);
            assertTrue(daemon);
        } finally {
            ((ExecutorService) executor).shutdownNow();
        }
    }

    @Test
    void theLivePushRunsOnItsOwnDaemonThread() throws Exception {
        Executor executor = new MailConfig().livePushExecutor();
        try {
            String name = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), executor)
                    .get(5, TimeUnit.SECONDS);
            boolean daemon = CompletableFuture.supplyAsync(Thread::currentThread, executor)
                    .get(5, TimeUnit.SECONDS).isDaemon();

            assertEquals("live-alert-push", name);
            assertTrue(daemon);
        } finally {
            ((ExecutorService) executor).shutdownNow();
        }
    }

    @Test
    void theQueueIsBoundedSoABusyServerCannotPileUpWork() throws Exception {
        Executor executor = new MailConfig().recoveryMailExecutor();
        CountDownLatch busy = new CountDownLatch(1);
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    busy.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, executor);
            for (int i = 0; i < MailConfig.QUEUE_CAPACITY; i++) {
                executor.execute(() -> { });
            }

            // One on the wire, eight waiting, and the next submission is refused rather than queued.
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            busy.countDown();
            ((ExecutorService) executor).shutdownNow();
        }
    }
}
