package net.officefloor.hq.app.claim;

/** A claim's status cannot move from where it is to the one asked for. */
public class StatusChangeRefusedException extends RuntimeException {

    public StatusChangeRefusedException(ClaimStatus from, ClaimStatus to) {
        super("A " + from + " claim cannot become " + to);
    }
}
