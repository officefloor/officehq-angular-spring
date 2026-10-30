package net.officefloor.hq.app.period;

/** A period that cannot be added: its name is taken, its dates are reversed, or it overlaps another. */
public class PeriodRefused extends RuntimeException {

    public PeriodRefused(String message) {
        super(message);
    }
}
