package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
        when(mongoTemplate.indexOps(Candle.class)).thenReturn(indexOperations);
        when(mongoTemplate.indexOps(BotConfiguration.class)).thenReturn(indexOperations);
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
    }
}
