package net.officefloor.hq.app.claim;

/** A claim's due-by cannot be set: it is not DRAFT, OPEN or PENDING. */
public class DueBySetRefusedException extends RuntimeException {

    public DueBySetRefusedException(String message) {
        super(message);
    }
}
