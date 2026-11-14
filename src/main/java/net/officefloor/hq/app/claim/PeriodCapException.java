package net.officefloor.hq.app.claim;

/** A claim cannot be opened: its person already has as many OPEN or PENDING claims in its period as
 *  the period's cap. */
public class PeriodCapException extends RuntimeException {

    public PeriodCapException(String period, int cap) {
        super("The person already has " + cap + " open claims in period " + period);
    }
}
