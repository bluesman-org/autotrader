package nl.jimkaplan.autotrader.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.util.List;

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

    /**
     * Index creation fails with a bare duplicate-key error when the data it constrains is
     * not unique yet, so at most this many offending values are reported.
     */
    private static final int MAX_REPORTED_DUPLICATES = 20;

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex(Candle.class, new Index()
                .on("market", Sort.Direction.ASC)
                .on("interval", Sort.Direction.ASC)
                .on("openTime", Sort.Direction.ASC)
                .unique());
        ensureBotIdIndex();
    }

    /**
     * The unique {@code botId} index has never been enforced, so an existing database may
     * contain duplicate bot ids. Those are reported with an actionable message instead of
     * letting index creation fail with the raw driver error.
     */
    private void ensureBotIdIndex() {
        List<String> duplicateBotIds = findDuplicateBotIds();
        if (!duplicateBotIds.isEmpty()) {
            throw new IllegalStateException("Cannot create the unique index on bot_configurations.botId: "
                    + "duplicate botId values exist " + duplicateBotIds
                    + ". Remove the duplicates and restart the application.");
        }
        ensureIndex(BotConfiguration.class, new Index()
                .on("botId", Sort.Direction.ASC)
                .unique());
    }

    private List<String> findDuplicateBotIds() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.group("botId").count().as("count"),
                Aggregation.match(Criteria.where("count").gt(1)),
                Aggregation.limit(MAX_REPORTED_DUPLICATES));
        return mongoTemplate.aggregate(aggregation, BotConfiguration.class, Document.class)
                .getMappedResults().stream()
                .map(result -> result.getString("_id"))
                .toList();
    }

    private void ensureIndex(Class<?> entityType, Index index) {
        String name = mongoTemplate.indexOps(entityType).ensureIndex(index);
        log.info("Ensured index '{}' on {}", name, entityType.getSimpleName());
    }
}
