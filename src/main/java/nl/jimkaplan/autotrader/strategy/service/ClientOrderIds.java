package nl.jimkaplan.autotrader.strategy.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Derives the client order IDs the live engine sends with real orders: one namespaced UUID per
 * (strategy, sequence). The derivation is deterministic so the boot reconciliation can re-derive
 * its own possible IDs and recognise orders whose records were lost to a backup restore —
 * nothing that does not match this namespace is ever adopted. Bitvavo requires the identifier to
 * be unique only across <em>open</em> orders, so a sequence number whose order was never accepted
 * (or has long filled) is safe to reuse.
 */
public final class ClientOrderIds {

    private static final String NAMESPACE = "autotrader";

    private ClientOrderIds() {
    }

    /**
     * The client order ID for one sequence number of one strategy.
     *
     * @param strategyId The strategy's document ID
     * @param sequence   The order sequence number, starting at 0
     * @return The deterministic UUID that is sent with the order and kept on its row
     */
    public static UUID forSequence(String strategyId, long sequence) {
        return UUID.nameUUIDFromBytes((NAMESPACE + ":" + strategyId + ":" + sequence)
                .getBytes(StandardCharsets.UTF_8));
    }
}
