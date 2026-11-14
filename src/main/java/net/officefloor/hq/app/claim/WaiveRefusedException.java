package net.officefloor.hq.app.claim;

/** A waiver of a claim's late charge that is refused: the claim is not OPEN or PENDING, or is already waived. */
public class WaiveRefusedException extends RuntimeException {

    public WaiveRefusedException(String message) {
        super(message);
    }
}
