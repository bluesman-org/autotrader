package nl.jimkaplan.autotrader.config;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.List;

import static nl.jimkaplan.autotrader.config.MongoIndexInitializer.BOT_ID_INDEX_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoIndexInitializerTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private IndexOperations indexOperations;

    @InjectMocks
    private MongoIndexInitializer mongoIndexInitializer;

    @Test
    void createsTheExpectedIndexes() {
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(BotConfiguration.class), eq(Document.class)))
                .thenReturn(aggregationResults());
        when(mongoTemplate.collectionExists(BotConfiguration.class)).thenReturn(true);
        when(mongoTemplate.indexOps(Candle.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(BotConfiguration.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(AppUser.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(Market.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(PasswordResetToken.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(BacktestRun.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(LiveOrder.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(LivePosition.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(PushSubscriptionDocument.class)).thenReturn(indexOperations);
        when(indexOperations.getIndexInfo()).thenReturn(List.of());
        when(indexOperations.ensureIndex(any(Index.class))).thenReturn("test-index");

        mongoIndexInitializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<Index> indexCaptor = ArgumentCaptor.forClass(Index.class);
        verify(indexOperations, times(12)).ensureIndex(indexCaptor.capture());
        List<Index> indexes = indexCaptor.getAllValues();

        Index candleIndex = indexes.get(0);
        assertEquals(3, candleIndex.getIndexKeys().size());
        assertEquals(1, candleIndex.getIndexKeys().get("market"));
        assertEquals(1, candleIndex.getIndexKeys().get("interval"));
        assertEquals(1, candleIndex.getIndexKeys().get("openTime"));
        assertEquals(Boolean.TRUE, candleIndex.getIndexOptions().get("unique"));

        Index botConfigurationIndex = indexes.get(1);
        assertEquals(1, botConfigurationIndex.getIndexKeys().size());
        assertEquals(1, botConfigurationIndex.getIndexKeys().get("botId"));
        assertEquals(Boolean.TRUE, botConfigurationIndex.getIndexOptions().get("unique"));
        assertEquals(BOT_ID_INDEX_NAME, botConfigurationIndex.getIndexOptions().get("name"));

        Index usernameIndex = indexes.get(2);
        assertEquals(1, usernameIndex.getIndexKeys().size());
        assertEquals(1, usernameIndex.getIndexKeys().get("username"));
        assertEquals(Boolean.TRUE, usernameIndex.getIndexOptions().get("unique"));

        Index emailIndex = indexes.get(3);
        assertEquals(1, emailIndex.getIndexKeys().size());
        assertEquals(1, emailIndex.getIndexKeys().get("email"));
        assertEquals(Boolean.TRUE, emailIndex.getIndexOptions().get("unique"));

        Index marketIndex = indexes.get(4);
        assertEquals(1, marketIndex.getIndexKeys().size());
        assertEquals(1, marketIndex.getIndexKeys().get("market"));
        assertEquals(Boolean.TRUE, marketIndex.getIndexOptions().get("unique"));

        Index tokenIndex = indexes.get(5);
        assertEquals(1, tokenIndex.getIndexKeys().size());
        assertEquals(1, tokenIndex.getIndexKeys().get("token_hash"));
        assertEquals(Boolean.TRUE, tokenIndex.getIndexOptions().get("unique"));

        Index backtestRunIndex = indexes.get(6);
        assertEquals(1, backtestRunIndex.getIndexKeys().size());
        assertEquals(-1, backtestRunIndex.getIndexKeys().get("runAt"));
        assertEquals(null, backtestRunIndex.getIndexOptions().get("unique"));

        Index liveOrderIndex = indexes.get(7);
        assertEquals(2, liveOrderIndex.getIndexKeys().size());
        assertEquals(1, liveOrderIndex.getIndexKeys().get("strategyId"));
        assertEquals(-1, liveOrderIndex.getIndexKeys().get("createdTime"));

        Index livePositionIndex = indexes.get(8);
        assertEquals(2, livePositionIndex.getIndexKeys().size());
        assertEquals(1, livePositionIndex.getIndexKeys().get("strategyId"));
        assertEquals(1, livePositionIndex.getIndexKeys().get("status"));

        Index liveOrderSignalIndex = indexes.get(9);
        assertEquals(1, liveOrderSignalIndex.getIndexKeys().size());
        assertEquals(1, liveOrderSignalIndex.getIndexKeys().get("signalOpenTime"));

        Index livePositionHistoryIndex = indexes.get(10);
        assertEquals(2, livePositionHistoryIndex.getIndexKeys().size());
        assertEquals(1, livePositionHistoryIndex.getIndexKeys().get("status"));
        assertEquals(1, livePositionHistoryIndex.getIndexKeys().get("entryTime"));

        Index pushSubscriptionIndex = indexes.get(11);
        assertEquals(1, pushSubscriptionIndex.getIndexKeys().size());
        assertEquals(1, pushSubscriptionIndex.getIndexKeys().get("endpoint"));
        assertEquals(Boolean.TRUE, pushSubscriptionIndex.getIndexOptions().get("unique"));
    }

    @Test
    void withLegacyBotIdIndexName_dropsItAndRecreatesUnderTheCanonicalName() {
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(BotConfiguration.class), eq(Document.class)))
                .thenReturn(aggregationResults());
        when(mongoTemplate.collectionExists(BotConfiguration.class)).thenReturn(true);
        when(mongoTemplate.indexOps(Candle.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(BotConfiguration.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(AppUser.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(Market.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(PasswordResetToken.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(BacktestRun.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(LiveOrder.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(LivePosition.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(PushSubscriptionDocument.class)).thenReturn(indexOperations);
        when(indexOperations.getIndexInfo()).thenReturn(List.of(new IndexInfo(
                List.of(IndexField.create("botId", Sort.Direction.ASC)), "botId_1", true, false, null)));
        when(indexOperations.ensureIndex(any(Index.class))).thenReturn("test-index");

        mongoIndexInitializer.run(new DefaultApplicationArguments());

        verify(indexOperations).dropIndex("botId_1");
        ArgumentCaptor<Index> indexCaptor = ArgumentCaptor.forClass(Index.class);
        verify(indexOperations, times(12)).ensureIndex(indexCaptor.capture());
        assertEquals(BOT_ID_INDEX_NAME, indexCaptor.getAllValues().get(1).getIndexOptions().get("name"));
    }

    @Test
    void withMissingCollection_skipsTheLegacyIndexCheck() {
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(BotConfiguration.class), eq(Document.class)))
                .thenReturn(aggregationResults());
        when(mongoTemplate.collectionExists(BotConfiguration.class)).thenReturn(false);
        when(mongoTemplate.indexOps(Candle.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(BotConfiguration.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(AppUser.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(Market.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(PasswordResetToken.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(BacktestRun.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(LiveOrder.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(LivePosition.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(PushSubscriptionDocument.class)).thenReturn(indexOperations);
        when(indexOperations.ensureIndex(any(Index.class))).thenReturn("test-index");

        mongoIndexInitializer.run(new DefaultApplicationArguments());

        verify(indexOperations, never()).getIndexInfo();
        verify(indexOperations, times(12)).ensureIndex(any(Index.class));
    }

    @Test
    void withDuplicateBotIds_throwsActionableErrorAndDoesNotCreateTheBotIdIndex() {
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(BotConfiguration.class), eq(Document.class)))
                .thenReturn(aggregationResults(new Document("_id", "duplicate-bot")));
        when(mongoTemplate.indexOps(Candle.class)).thenReturn(indexOperations);
        when(indexOperations.ensureIndex(any(Index.class))).thenReturn("test-index");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> mongoIndexInitializer.run(new DefaultApplicationArguments()));

        assertTrue(exception.getMessage().contains("duplicate-bot"));
        assertTrue(exception.getMessage().contains("bot_configurations.botId"));
        verify(mongoTemplate, never()).indexOps(BotConfiguration.class);
    }

    private static AggregationResults<Document> aggregationResults(Document... results) {
        return new AggregationResults<>(List.of(results), new Document());
    }
}
