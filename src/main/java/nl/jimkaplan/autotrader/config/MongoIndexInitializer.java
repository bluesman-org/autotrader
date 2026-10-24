package nl.jimkaplan.autotrader.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.model.document.Market;
import nl.jimkaplan.autotrader.model.document.PasswordResetToken;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.PushSubscriptionDocument;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates the indexes the application relies on at startup.
 *
 * <p>{@code spring.data.mongodb.auto-index-creation} is off by default (see
 * {@link MongoConfig}), so the {@code @Indexed} annotations alone would not create these
 * indexes: they are ensured explicitly here, which also lets the duplicate-{@code botId}
 * check run before the unique index and keeps the definitions unit-tested. Index creation
 * is idempotent.</p>
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

    /**
     * Index name used by the {@code @Indexed} annotation on {@code BotConfiguration.botId};
     * keeping the explicit creation consistent lets both index-creation paths coexist.
     */
    static final String BOT_ID_INDEX_NAME = "botId";

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex(Candle.class, new Index()
                .on("market", Sort.Direction.ASC)
                .on("interval", Sort.Direction.ASC)
                .on("openTime", Sort.Direction.ASC)
                .unique());
        ensureBotIdIndex();
        ensureIndex(AppUser.class, new Index().on("username", Sort.Direction.ASC).unique());
        ensureIndex(AppUser.class, new Index().on("email", Sort.Direction.ASC).unique());
        // One document per market symbol: the catalogue is read by symbol everywhere and a
        // duplicate would offer the same pair twice.
        ensureIndex(Market.class, new Index().on("market", Sort.Direction.ASC).unique());
        ensureIndex(PasswordResetToken.class,
                new Index().on("token_hash", Sort.Direction.ASC).unique());
        ensureIndex(BacktestRun.class, new Index()
                .on("runAt", Sort.Direction.DESC));
        ensureIndex(LiveOrder.class, new Index()
                .on("strategyId", Sort.Direction.ASC)
                .on("createdTime", Sort.Direction.DESC));
        ensureIndex(LivePosition.class, new Index()
                .on("strategyId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC));
        // The history page reads orders by signal time and positions by status plus entry time.
        ensureIndex(LiveOrder.class, new Index()
                .on("signalOpenTime", Sort.Direction.ASC));
        ensureIndex(LivePosition.class, new Index()
                .on("status", Sort.Direction.ASC)
                .on("entryTime", Sort.Direction.ASC));
        // A browser subscribes once per endpoint; re-subscribing replaces the keys on the same
        // document, so the endpoint is the natural unique key.
        ensureIndex(PushSubscriptionDocument.class,
                new Index().on("endpoint", Sort.Direction.ASC).unique());
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
        dropLegacyBotIdIndex();
        ensureIndex(BotConfiguration.class, new Index()
                .on("botId", Sort.Direction.ASC)
                .unique()
                .named(BOT_ID_INDEX_NAME));
    }

    /**
     * The {@code @Indexed} annotation (active when {@code spring.data.mongodb.auto-index-creation}
     * is enabled) derives the name 'botId', while earlier versions of this initializer let the
     * server generate 'botId_1'. MongoDB refuses two indexes with the same key but different
     * names, so a legacy index is dropped and recreated under the canonical name.
     */
    private void dropLegacyBotIdIndex() {
        if (!mongoTemplate.collectionExists(BotConfiguration.class)) {
            return;
        }
        for (IndexInfo indexInfo : mongoTemplate.indexOps(BotConfiguration.class).getIndexInfo()) {
            boolean sameKey = indexInfo.getIndexFields().size() == 1
                    && "botId".equals(indexInfo.getIndexFields().getFirst().getKey());
            if (sameKey && !BOT_ID_INDEX_NAME.equals(indexInfo.getName())) {
                log.info("Recreating index '{}' on bot_configurations.botId as '{}'",
                        indexInfo.getName(), BOT_ID_INDEX_NAME);
                mongoTemplate.indexOps(BotConfiguration.class).dropIndex(indexInfo.getName());
            }
        }
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
