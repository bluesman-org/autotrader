package nl.jimkaplan.autotrader.repository;

/**
 * The database's summary of a strategy's closed round trips: the count, the total realised profit,
 * how many ended at or above zero, and the sum of those wins. The losses and their average follow
 * from these, so the live page never reads the history itself.
 */
public interface RoundTripStats {

    /**
     * @return The number of closed round trips
     */
    long getCount();

    /**
     * @return The summed realised profit, in EUR, after fees
     */
    double getTotal();

    /**
     * @return The number of round trips that ended at or above zero
     */
    long getWins();

    /**
     * @return The summed profit of the winning round trips, in EUR
     */
    double getWinTotal();
}
