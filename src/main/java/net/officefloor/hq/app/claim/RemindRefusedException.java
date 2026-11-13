package net.officefloor.hq.app.claim;

/** A claim that cannot be reminded: it is not OPEN or PENDING, or was already reminded on the sheet's today. */
public class RemindRefusedException extends RuntimeException {

    public RemindRefusedException(String message) {
        super(message);
    }
}
