package net.officefloor.hq.app.claim;

/** A claim cannot be re-dated: it is not OPEN or PENDING. */
public class RedateRefusedException extends RuntimeException {

    public RedateRefusedException(String message) {
        super(message);
    }
}
