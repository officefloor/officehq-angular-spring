package net.officefloor.hq.app.claim;

/** A hold or release of a claim that is refused: the claim is not OPEN or PENDING. */
public class HoldRefusedException extends RuntimeException {

    public HoldRefusedException(String message) {
        super(message);
    }
}
