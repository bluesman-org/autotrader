package nl.jimkaplan.autotrader.strategy.controller;

import lombok.Data;

import java.util.List;

/**
 * The Bitvavo-ledger tab's filter, view and paging state, bound from the query string.
 */
@Data
public class LedgerRequest {

    /**
     * The selected time preset, when no custom range is given.
     */
    private String period;

    /**
     * The custom range's start date, {@code yyyy-MM-dd}.
     */
    private String fromDate;

    /**
     * The custom range's start time, {@code H:mm} or {@code HH}.
     */
    private String fromTime;

    /**
     * The custom range's end date, {@code yyyy-MM-dd}.
     */
    private String toDate;

    /**
     * The custom range's end time, {@code H:mm} or {@code HH}.
     */
    private String toTime;

    /**
     * The selected side ({@code buy}/{@code sell}), or empty for all.
     */
    private String side;

    /**
     * The selected market (e.g. {@code BTC-EUR}); {@code all} merges every catalogue pair, and
     * empty selects the engine's market.
     */
    private String market;

    /**
     * The selected order types (e.g. {@code market}, {@code limit}); empty selects every type.
     */
    private List<String> type;

    /**
     * The selected statuses ({@code open}, {@code awaitingTrigger}, {@code partiallyFilled},
     * {@code filled}, {@code canceled}, {@code expired}); empty selects every status.
     */
    private List<String> status;

    /**
     * The selected view: {@code orders} (the default) or {@code raw}.
     */
    private String view;

    /**
     * The order table's sort column: {@code date} (the default), {@code side}, {@code type},
     * {@code market}, {@code amount}, {@code price}, {@code status}, {@code balance},
     * {@code fee}, {@code netCash}, {@code fills} or {@code pnl}.
     */
    private String sort;

    /**
     * The sort direction: {@code asc}, or {@code desc} when absent.
     */
    private String dir;

    /**
     * The order page number, 1-based.
     */
    private Integer page;

    /**
     * The rows per page for the orders table; the raw view uses its own sizes.
     */
    private Integer size;
}
