package net.officefloor.hq.app.period;

/** A claim due by a date inside a closed period cannot be added, edited, opened, settled or voided. */
public class ClosedPeriodRefused extends RuntimeException {

    public ClosedPeriodRefused(String period) {
        super("The period " + period + " is closed.");
    }
}
