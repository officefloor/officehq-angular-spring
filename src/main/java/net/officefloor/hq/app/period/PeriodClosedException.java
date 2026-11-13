package net.officefloor.hq.app.period;

/** A change refused because it touches a closed period: closing it again, or a claim dated in it. */
public class PeriodClosedException extends RuntimeException {

    public PeriodClosedException(String message) {
        super(message);
    }
}
