package net.officefloor.hq.app.claim;

/** A claim that cannot be deferred: it is not OPEN, or the date is not after the sheet's today. */
public class DeferRefusedException extends RuntimeException {

    public DeferRefusedException(String message) {
        super(message);
    }
}
