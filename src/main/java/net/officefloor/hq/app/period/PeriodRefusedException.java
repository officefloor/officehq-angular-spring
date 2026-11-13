package net.officefloor.hq.app.period;

/** A period that cannot be added: its name is taken, its dates run backwards or it overlaps another. */
public class PeriodRefusedException extends RuntimeException {

    public PeriodRefusedException(String message) {
        super(message);
    }
}
