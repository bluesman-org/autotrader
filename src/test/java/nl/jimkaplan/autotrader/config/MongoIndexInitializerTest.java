package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
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
    void createsUniqueIndexesForCandlesAndBotConfigurations() {
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(BotConfiguration.class), eq(Document.class)))
                .thenReturn(aggregationResults());
        when(mongoTemplate.collectionExists(BotConfiguration.class)).thenReturn(true);
        when(mongoTemplate.indexOps(Candle.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(BotConfiguration.class)).thenReturn(indexOperations);
        when(indexOperations.getIndexInfo()).thenReturn(List.of());
        when(indexOperations.ensureIndex(any(Index.class))).thenReturn("test-index");

        mongoIndexInitializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<Index> indexCaptor = ArgumentCaptor.forClass(Index.class);
        verify(indexOperations, times(2)).ensureIndex(indexCaptor.capture());
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
    }

    @Test
    void withLegacyBotIdIndexName_dropsItAndRecreatesUnderTheCanonicalName() {
        when(mongoTemplate.aggregate(any(Aggregation.class), eq(BotConfiguration.class), eq(Document.class)))
                .thenReturn(aggregationResults());
        when(mongoTemplate.collectionExists(BotConfiguration.class)).thenReturn(true);
        when(mongoTemplate.indexOps(Candle.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(BotConfiguration.class)).thenReturn(indexOperations);
        when(indexOperations.getIndexInfo()).thenReturn(List.of(new IndexInfo(
                List.of(IndexField.create("botId", Sort.Direction.ASC)), "botId_1", true, false, null)));
        when(indexOperations.ensureIndex(any(Index.class))).thenReturn("test-index");

        mongoIndexInitializer.run(new DefaultApplicationArguments());

        verify(indexOperations).dropIndex("botId_1");
        ArgumentCaptor<Index> indexCaptor = ArgumentCaptor.forClass(Index.class);
        verify(indexOperations, times(2)).ensureIndex(indexCaptor.capture());
        assertEquals(BOT_ID_INDEX_NAME, indexCaptor.getAllValues().get(1).getIndexOptions().get("name"));
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
