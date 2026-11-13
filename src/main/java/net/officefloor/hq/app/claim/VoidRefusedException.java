package net.officefloor.hq.app.claim;

/** Only a steward voids a SETTLED claim, and only within 7 days of its settlement. */
public class VoidRefusedException extends RuntimeException {

    public VoidRefusedException(String message) {
        super(message);
    }
}
