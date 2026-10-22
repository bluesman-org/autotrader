package nl.jimkaplan.autotrader.strategy.controller;

import java.util.List;

/**
 * The reconcile page's model: the open position and the exchange sells that may have closed it.
 *
 * @param strategyId   The strategy ID
 * @param name         The strategy name
 * @param market       The market
 * @param interval     The interval
 * @param dryRun       Whether the strategy is a dry run
 * @param quantityText The open position's size
 * @param entryText    When and at what price the position was opened
 * @param entryFeeText The fee paid on entry
 * @param candidates   The exchange sells that can be attached
 * @param note         Why the list is empty or incomplete, or null when it is complete
 */
public record ReconcileView(String strategyId, String name, String market, String interval,
                            boolean dryRun, String quantityText, String entryText, String entryFeeText,
                            List<ExternalCloseView> candidates, String note) {

    /** Copy the candidates so the view cannot be mutated from outside. */
    public ReconcileView {
        candidates = List.copyOf(candidates);
    }
}
