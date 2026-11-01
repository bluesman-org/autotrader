package nl.jimkaplan.autotrader.repository;

import com.mongodb.ConnectionString;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Executes the queries the history page and the live engine depend on against a real MongoDB.
 *
 * <p>{@link HistoryRangeQueryTest} builds the same repository proxies and materializes their query
 * documents without a database, which catches a query Spring Data cannot construct. This class goes
 * the rest of the way: a document that Mongo accepts can still name the wrong field, carry the wrong
 * bound or return the wrong order, and none of that shows up until the query runs against stored
 * documents. The failure that motivated both tests — the history page's range queries — reached a
 * deploy for exactly that reason.</p>
 *
 * <p>The suite is skipped unless {@code MONGODB_TEST_URI} names a database whose name ends with
 * {@code _test}; it drops that database before every test. CI provides one through the coverage
 * workflow's service container, which runs MongoDB 7.0 because 8.0 and later refuse to start on
 * the runner's kernel (SERVER-121912). Locally:</p>
 *
 * <pre>{@code
 * docker run -d --rm -p 127.0.0.1:27018:27017 mongo:7.0
 * MONGODB_TEST_URI=mongodb://127.0.0.1:27018/autotrader_query_test ./mvnw test -Dtest=MongoQueryExecutionTest
 * }</pre>
 */
@DataMongoTest
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".*\\S.*")
class MongoQueryExecutionTest {

    @Autowired
    private LivePositionRepository positions;

    @Autowired
    private LiveOrderRepository orders;

    @Autowired
    private CandleRepository candles;

    @Autowired
    private LiveStrategyRepository strategies;

    @Autowired
    private MongoTemplate mongoTemplate;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        String uri = System.getenv("MONGODB_TEST_URI");
        if (uri == null || uri.isBlank()) {
            return;
        }
        String database = new ConnectionString(uri).getDatabase();
        // The suite drops its database, so it refuses to run against anything but an explicitly
        // named test one; pointing it at "autotrader" must fail before the first drop.
        if (database == null || !database.endsWith("_test")) {
            throw new IllegalStateException(
                    "MONGODB_TEST_URI must name a database ending in _test, because the suite drops it");
        }
        registry.add("spring.mongodb.uri", () -> uri);
        registry.add("spring.mongodb.database", () -> database);
    }

    @BeforeEach
    void clearDatabase() {
        mongoTemplate.getDb().drop();
    }

    @Test
    void thePositionRangeQueryFiltersOnStatusAndKeepsItsInclusiveBounds() {
        positions.saveAll(List.of(
                position("CLOSED", 99),
                position("CLOSED", 100),
                position("CLOSED", 200),
                position("CLOSED", 201),
                position("OPEN", 150)));

        List<Long> entryTimes = positions.findByStatusAndEntryTimeRange("CLOSED", 100, 200).stream()
                .map(LivePosition::getEntryTime)
                .sorted()
                .toList();

        assertEquals(List.of(100L, 200L), entryTimes);
    }

    @Test
    void theOrderRangeQueryKeepsItsInclusiveBounds() {
        orders.saveAll(List.of(
                order("s1", 99),
                order("s1", 100),
                order("s1", 200),
                order("s1", 201)));

        List<Long> signalTimes = orders.findBySignalOpenTimeRange(100, 200).stream()
                .map(LiveOrder::getSignalOpenTime)
                .sorted()
                .toList();

        assertEquals(List.of(100L, 200L), signalTimes);
    }

    @Test
    void theCandleRangeQueryReturnsOneMarketAndIntervalInOpenTimeOrder() {
        candles.saveAll(List.of(
                candle("BTC-EUR", "1h", 200),
                candle("BTC-EUR", "1h", 100),
                candle("BTC-EUR", "1h", 99),
                candle("BTC-EUR", "1h", 201),
                candle("BTC-EUR", "5m", 150),
                candle("ETH-EUR", "1h", 150)));

        List<Long> openTimes = candles.findCandlesInRange("BTC-EUR", "1h", 100, 200).stream()
                .map(Candle::getOpenTime)
                .toList();

        assertEquals(List.of(100L, 200L), openTimes);
    }

    @Test
    void theLivePageReturnsTheEightNewestOrdersOfItsOwnStrategy() {
        for (int i = 1; i <= 10; i++) {
            orders.save(order("s1", i));
        }
        orders.save(order("s2", 11));

        List<Long> created = orders.findTop8ByStrategyIdOrderByCreatedTimeDesc("s1").stream()
                .map(order -> order.getCreatedTime().toEpochMilli())
                .toList();

        assertEquals(List.of(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L), created);
    }

    @Test
    void theManualCloseCheckReadsItsSignalTimeInclusively() {
        orders.save(LiveOrder.builder()
                .strategyId("s1")
                .market("BTC-EUR")
                .side("SELL")
                .status("FILLED")
                .dryRun(false)
                .signalOpenTime(100)
                .build());

        assertTrue(orders.existsByStrategyIdAndDryRunFalseAndSideAndStatusAndSignalOpenTimeGreaterThanEqual(
                "s1", "SELL", "FILLED", 100));
        assertFalse(orders.existsByStrategyIdAndDryRunFalseAndSideAndStatusAndSignalOpenTimeGreaterThanEqual(
                "s1", "SELL", "FILLED", 101));
        assertFalse(orders.existsByStrategyIdAndDryRunFalseAndSideAndStatusAndSignalOpenTimeGreaterThanEqual(
                "s1", "BUY", "FILLED", 100));

        // A simulated sell in the same position: side, status and signal time all match, so only
        // the real-order term can keep this false.
        orders.save(LiveOrder.builder()
                .strategyId("s1")
                .market("BTC-EUR")
                .side("SELL")
                .status("FILLED")
                .dryRun(true)
                .signalOpenTime(200)
                .build());
        assertFalse(orders.existsByStrategyIdAndDryRunFalseAndSideAndStatusAndSignalOpenTimeGreaterThanEqual(
                "s1", "SELL", "FILLED", 150));
    }

    @Test
    void theSeriesAnchorIsFixedByTheFirstWriter() {
        LiveStrategy strategy = strategies.save(strategy(0));

        assertEquals(1, strategies.updateSeriesStartTime(strategy.getId(), 111L));
        assertEquals(111L, strategies.findById(strategy.getId()).orElseThrow().getSeriesStartTime());

        // The second writer loses: the anchor must never move once it is set.
        assertEquals(0, strategies.updateSeriesStartTime(strategy.getId(), 222L));
        assertEquals(111L, strategies.findById(strategy.getId()).orElseThrow().getSeriesStartTime());
    }

    @Test
    void theSeriesAnchorIsWrittenWhenTheFieldIsAbsent() {
        LiveStrategy strategy = strategies.save(strategy(0));
        mongoTemplate.updateFirst(Query.query(where("_id").is(strategy.getId())),
                new Update().unset("series_start_time"), LiveStrategy.class);

        assertEquals(1, strategies.updateSeriesStartTime(strategy.getId(), 111L));
        assertEquals(111L, strategies.findById(strategy.getId()).orElseThrow().getSeriesStartTime());
    }

    @Test
    void theOrderSequenceNeverMovesBackwards() {
        LiveStrategy strategy = strategies.save(strategy(0));

        strategies.updateNextOrderSequence(strategy.getId(), 5);
        strategies.updateNextOrderSequence(strategy.getId(), 3);

        assertEquals(5, strategies.findById(strategy.getId()).orElseThrow().getNextOrderSequence());
    }

    @Test
    void theLastStartedTimeIsStoredAsAnInstant() {
        LiveStrategy strategy = strategies.save(strategy(0));
        Instant started = Instant.ofEpochMilli(1_790_000_000_000L);

        strategies.updateLastStartedTime(strategy.getId(), started);

        assertEquals(started, strategies.findById(strategy.getId()).orElseThrow().getLastStartedTime());
    }

    private static LivePosition position(String status, long entryTime) {
        return LivePosition.builder()
                .strategyId("s1")
                .market("BTC-EUR")
                .status(status)
                .quantity(0.01)
                .entryPrice(50_000)
                .entryTime(entryTime)
                .dryRun(true)
                .build();
    }

    private static LiveOrder order(String strategyId, long createdAtMillis) {
        Instant createdTime = Instant.ofEpochMilli(createdAtMillis);
        return LiveOrder.builder()
                .strategyId(strategyId)
                .market("BTC-EUR")
                .side("BUY")
                .status("DRY_RUN")
                .dryRun(true)
                .signalOpenTime(createdAtMillis)
                .createdTime(createdTime)
                .build();
    }

    private static Candle candle(String market, String interval, long openTime) {
        return Candle.builder()
                .market(market)
                .interval(interval)
                .openTime(openTime)
                .open(BigDecimal.valueOf(50_000))
                .high(BigDecimal.valueOf(50_100))
                .low(BigDecimal.valueOf(49_900))
                .close(BigDecimal.valueOf(50_050))
                .volume(BigDecimal.ONE)
                .build();
    }

    private static LiveStrategy strategy(long seriesStartTime) {
        return LiveStrategy.builder()
                .name("query execution test")
                .market("BTC-EUR")
                .interval("1h")
                .seriesStartTime(seriesStartTime)
                .build();
    }
}
