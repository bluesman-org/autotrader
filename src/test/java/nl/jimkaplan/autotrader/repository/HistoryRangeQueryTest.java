package nl.jimkaplan.autotrader.repository;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.DbCallback;
import org.springframework.data.mongodb.core.ExecutableFindOperation;
import org.springframework.data.mongodb.core.MongoExceptionTranslator;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds the history page's two range queries without a database. They were once derived method
 * names with two bounds on the same field, which Spring Data cannot construct — Mongo's
 * {@code Document} rejects the second key — and no mocked service test can see that; the failure
 * reached a deploy. These tests invoke the repository proxies against a mocked template (the query
 * is built before anything reaches Mongo), so a query Spring Data cannot construct fails here, and
 * the captured query's document is materialized in the assertions — the step the production failure
 * surfaced at — which pins the inclusive bounds and the stored field names.
 */
@ExtendWith(MockitoExtension.class)
class HistoryRangeQueryTest {

    @Mock
    private MongoTemplate mongoTemplate;

    private LivePositionRepository positions;
    private LiveOrderRepository orders;

    private Query emittedQuery;

    @BeforeEach
    void setUp() throws Exception {
        MongoDatabaseFactory databaseFactory = mock(MongoDatabaseFactory.class);
        when(databaseFactory.getExceptionTranslator()).thenReturn(new MongoExceptionTranslator());
        // The application's context contributes the JSR-310 conversions; without them the mapping
        // context treats an Instant field as a nested document and reflection into java.time fails.
        MongoCustomConversions conversions = MongoCustomConversions.create(adapter -> {
        });
        MongoMappingContext mappingContext = new MongoMappingContext();
        mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        MappingMongoConverter converter = new MappingMongoConverter(
                new DefaultDbRefResolver(databaseFactory), mappingContext);
        // The template's converter reads its codec registry from the database factory; a query with
        // parameters needs one to bind them.
        converter.setCodecRegistryProvider(databaseFactory);
        converter.afterPropertiesSet();
        when(mongoTemplate.getConverter()).thenReturn(converter);
        // The query codec is fetched by running a callback against the database; the mock invokes it
        // with a database whose codec registry is the driver's default.
        MongoDatabase database = mock(MongoDatabase.class);
        when(database.getCodecRegistry()).thenReturn(MongoClientSettings.getDefaultCodecRegistry());
        when(mongoTemplate.execute(any(DbCallback.class)))
                .thenAnswer(invocation -> ((DbCallback<?>) invocation.getArgument(0)).doInDB(database));
        stubFind();

        MongoRepositoryFactory factory = new MongoRepositoryFactory(mongoTemplate);
        positions = factory.getRepository(LivePositionRepository.class);
        orders = factory.getRepository(LiveOrderRepository.class);
    }

    /**
     * The repositories read through the fluent find API; the chain ends in an empty result and
     * records the query the repository built. Nothing here reaches Mongo.
     */
    @SuppressWarnings("unchecked")
    private void stubFind() {
        ExecutableFindOperation.TerminatingFind<Object> terminating =
                mock(ExecutableFindOperation.TerminatingFind.class);
        when(terminating.all()).thenReturn(List.of());

        ExecutableFindOperation.FindWithQuery<Object> find = mock(ExecutableFindOperation.FindWithQuery.class);
        when(find.matching(any(Query.class))).thenAnswer(invocation -> {
            emittedQuery = invocation.getArgument(0);
            return terminating;
        });

        ExecutableFindOperation.ExecutableFind<Object> executable =
                mock(ExecutableFindOperation.ExecutableFind.class);
        when(executable.as(any(Class.class))).thenReturn(find);

        when(mongoTemplate.query(any(Class.class))).thenReturn(executable);
    }

    @Test
    void thePositionRangeQueryCarriesBothInclusiveBounds() {
        positions.findByStatusAndEntryTimeRange("CLOSED", 100L, 200L);

        assertNotNull(emittedQuery, "the repository built a query");
        Document document = emittedQuery.getQueryObject();
        assertEquals("CLOSED", document.getString("status"));
        Document entryTime = document.get("entry_time", Document.class);
        assertEquals(100L, entryTime.get("$gte"));
        assertEquals(200L, entryTime.get("$lte"));
    }

    @Test
    void theOrderRangeQueryCarriesBothInclusiveBounds() {
        orders.findBySignalOpenTimeRange(100L, 200L);

        assertNotNull(emittedQuery, "the repository built a query");
        Document signal = emittedQuery.getQueryObject().get("signal_open_time", Document.class);
        assertEquals(100L, signal.get("$gte"));
        assertEquals(200L, signal.get("$lte"));
    }
}
