package nl.jimkaplan.autotrader.config;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Optional;

/**
 * Copies the Bitvavo API key that used to live inside a bot configuration into the system-wide
 * credentials, once. Bitvavo shows an API secret only when it is created, so an existing
 * installation cannot re-enter it: without the copy, a real strategy would stop trading and the
 * ledger would stop reading the account. The key belongs to the account, so nothing reads the bot
 * afterwards.
 *
 * <p>The source is the configuration the engine used to name — the newest live strategy's bot —
 * and otherwise the first active configuration that carries a key. A failure here is logged and
 * leaves the page to say the key is missing rather than stopping the application.</p>
 */
@Slf4j
@Component
public class BitvavoCredentialsInitializer implements ApplicationRunner {

    private final BitvavoCredentialsService credentialsService;
    private final BotConfigurationService botConfigurationService;
    private final LiveStrategyService liveStrategyService;

    public BitvavoCredentialsInitializer(BitvavoCredentialsService credentialsService,
                                         BotConfigurationService botConfigurationService,
                                         LiveStrategyService liveStrategyService) {
        this.credentialsService = credentialsService;
        this.botConfigurationService = botConfigurationService;
        this.liveStrategyService = liveStrategyService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (credentialsService.configured()) {
            return;
        }
        Optional<BotConfiguration> source = sourceBot();
        if (source.isEmpty()) {
            return;
        }
        BotConfiguration bot = source.get();
        try {
            credentialsService.save(bot.getApiKey(), bot.getApiSecret(), bot.getOperatorId());
            log.info("Copied the Bitvavo API key from bot configuration {} into the system-wide "
                    + "credentials", bot.getBotId());
        } catch (RuntimeException e) {
            log.warn("Could not copy the Bitvavo API key from bot configuration {}: {}",
                    bot.getBotId(), e.getMessage());
        }
    }

    private Optional<BotConfiguration> sourceBot() {
        Optional<BotConfiguration> fromEngine = liveStrategyService.all().stream()
                .filter(strategy -> strategy.getBotId() != null && !strategy.getBotId().isBlank())
                .max(Comparator.comparing(LiveStrategy::getCreatedTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .flatMap(strategy -> botConfigurationService.getBotConfiguration(strategy.getBotId()))
                .filter(BitvavoCredentialsInitializer::hasKey);
        return fromEngine.or(() -> botConfigurationService.getAllBotConfigurations().stream()
                .filter(BitvavoCredentialsInitializer::hasKey)
                .min(Comparator.comparing(BotConfiguration::getBotId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))));
    }

    private static boolean hasKey(BotConfiguration bot) {
        return bot.getApiKey() != null && !bot.getApiKey().isBlank()
                && bot.getApiSecret() != null && !bot.getApiSecret().isBlank();
    }
}
