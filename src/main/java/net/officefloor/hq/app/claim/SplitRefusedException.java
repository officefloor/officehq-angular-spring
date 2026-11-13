package net.officefloor.hq.app.claim;

/** A claim that cannot be split: it is not OPEN or is deferred, or the amount is not above 0.00 and
 *  below the claim's. */
public class SplitRefusedException extends RuntimeException {

    public SplitRefusedException(String message) {
        super(message);
    }
}
