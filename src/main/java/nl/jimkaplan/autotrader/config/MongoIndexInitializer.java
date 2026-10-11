package nl.jimkaplan.autotrader.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Creates the indexes the application relies on at startup.
 *
 * <p>Spring Data's {@code spring.data.mongodb.auto-index-creation} property is not
 * effective with the custom {@link MongoConfig}, so the indexes are ensured explicitly
 * here instead of relying on the {@code @Indexed} annotations. Index creation is
 * idempotent.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoIndexInitializer implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex(Candle.class, new Index()
                .on("market", Sort.Direction.ASC)
                .on("interval", Sort.Direction.ASC)
                .on("openTime", Sort.Direction.ASC)
                .unique());
        ensureIndex(BotConfiguration.class, new Index()
                .on("botId", Sort.Direction.ASC)
                .unique());
    }

    private void ensureIndex(Class<?> entityType, Index index) {
        String name = mongoTemplate.indexOps(entityType).ensureIndex(index);
        log.info("Ensured index '{}' on {}", name, entityType.getSimpleName());
    }
}
