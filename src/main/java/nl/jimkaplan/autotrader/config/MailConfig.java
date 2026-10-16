package nl.jimkaplan.autotrader.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The single thread the recovery mail leaves on, so the request that asked for it answers at once —
 * an address that is the account's must not be distinguishable from one that is not by how long the
 * page takes.
 */
@Configuration
public class MailConfig {

    @Bean(destroyMethod = "shutdown")
    public Executor recoveryMailExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "recovery-mail");
            thread.setDaemon(true);
            return thread;
        });
    }
}
