package nl.jimkaplan.autotrader.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Represents the response data for an asset.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetAssetDataResponse {

    /**
     * The asset symbol.
     * Example: BTC
     */
    private String symbol;

    /**
     * The full name of the asset.
     * Example: Bitcoin
     */
    private String name;

    /**
     * The number of decimal digits for this asset.
     * Example: 8
     */
    private int decimals;

    /**
     * The fee for depositing the asset.
     * Example: 0
     */
    private BigDecimal depositFee;

    /**
     * The minimum number of network confirmations to credit the asset to your account.
     * Example: 10
     */
    private int depositConfirmations;

    /**
     * The status of the asset being deposited.
     * Possible values: [OK, MAINTENANCE, DELISTED]
     */
    private String depositStatus;

    /**
     * The fee for withdrawing the asset.
     * Example: 0.2
     */
    private BigDecimal withdrawalFee;

    /**
     * The minimum amount that can be withdrawn.
     * Example: 0.2
     */
    private BigDecimal withdrawalMinAmount;

    /**
     * The status of the asset being withdrawn.
     * Possible values: [OK, MAINTENANCE, DELISTED]
     */
    private String withdrawalStatus;

    /**
     * The list of supported networks.
     * Example: ["Mainnet"]
     */
    private List<String> networks;

    /**
     * The reason if the withdrawalStatus or depositStatus is not OK.
     */
    private String message;
}