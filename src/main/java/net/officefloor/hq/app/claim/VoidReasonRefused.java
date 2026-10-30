package net.officefloor.hq.app.claim;

/** A void reason, or a void for want of one, was refused: nothing was written. */
public class VoidReasonRefused extends RuntimeException {

    public VoidReasonRefused(String message) {
        super(message);
    }
}
