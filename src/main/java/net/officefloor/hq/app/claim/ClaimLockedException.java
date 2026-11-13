package net.officefloor.hq.app.claim;

/** Another user has the claim's editor open: the claim is locked to them until they save or cancel it. */
public class ClaimLockedException extends RuntimeException {

    public ClaimLockedException(String lockedBy) {
        super("The claim is being edited by " + lockedBy);
    }
}
