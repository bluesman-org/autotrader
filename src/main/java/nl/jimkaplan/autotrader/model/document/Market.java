package nl.jimkaplan.autotrader.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * A trading pair the system offers: one row of the pair catalogue the strategy forms and the
 * account ledger read their choices from.
 *
 * <p>The catalogue is not a copy of Bitvavo's offering. It starts from the pairs the account
 * itself traded — discovered from the account history with the system-wide key — and can be
 * extended by hand for a pair the account received another way (a deposit, say). A pair is
 * disabled rather than deleted: a refresh would bring a traded pair back, and a running strategy
 * may still name it.</p>
 *
 * <p>Maps to the {@code markets} collection. The market symbol is a normal field with a unique
 * index, not the document id, so the document keeps the base class's generated id like every
 * other document.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "markets")
public class Market extends BaseDocument {

    /**
     * The source of a pair that was found in the account's own trade history.
     */
    public static final String SOURCE_DISCOVERED = "discovered";

    /**
     * The source of a pair an operator added by hand.
     */
    public static final String SOURCE_MANUAL = "manual";

    /**
     * Market in Bitvavo format, e.g. "BTC-EUR". Only EUR-quoted markets are stored: the engine,
     * the backtester and the ledger all size and display in euro.
     */
    @Field("market")
    private String market;

    /**
     * The asset traded on the market (e.g. "BTC").
     */
    @Field("base")
    private String base;

    /**
     * The currency a unit is priced in (e.g. "EUR").
     */
    @Field("quote")
    private String quote;

    /**
     * Whether the forms and the ledger offer the pair. A disabled pair stays in the catalogue with
     * its history, and a live strategy that trades it keeps it visible regardless.
     */
    @Field("enabled")
    private boolean enabled;

    /**
     * {@link #SOURCE_DISCOVERED} or {@link #SOURCE_MANUAL}.
     */
    @Field("source")
    private String source;

    /**
     * Bitvavo's status for the market when it was last read, e.g. {@code trading}; null when the
     * exchange could not be asked.
     */
    @Field("status")
    private String status;

    /**
     * The account's first trade on the pair, when it has one.
     */
    @Field("first_traded_at")
    private Instant firstTradedAt;

    /**
     * The account's last trade on the pair, when it has one.
     */
    @Field("last_traded_at")
    private Instant lastTradedAt;

    /**
     * How many buys and sells the account made on the pair, as of the last discovery.
     */
    @Field("trade_count")
    private int tradeCount;

    /**
     * When the pair entered the catalogue.
     */
    @Field("added_time")
    private Instant addedTime;
}
