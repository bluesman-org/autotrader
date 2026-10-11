package nl.jimkaplan.autotrader.strategy.rule;

/**
 * How the conditions of a rule group combine.
 */
public enum LogicalOperator {

    /**
     * All conditions must hold (AND).
     */
    ALL,

    /**
     * At least one condition must hold (OR).
     */
    ANY
}
