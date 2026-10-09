package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response model for the Bitvavo transaction history.
 * Based on the <a href="https://docs.bitvavo.com/docs/rest-api/get-account-history">Bitvavo API documentation</a>.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetAccountHistoryResponse {

    /**
     * The transactions on the requested page.
     */
    private List<Transaction> items;

    /**
     * The current page number.
     */
    private Integer currentPage;

    /**
     * The total number of pages.
     */
    private Integer totalPages;

    /**
     * The maximum number of items per page.
     */
    private Integer maxItems;
}
