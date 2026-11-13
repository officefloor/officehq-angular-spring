package net.officefloor.hq.app.claim;

/** The user who opened a claim cannot settle it: another user must. */
public class OwnClaimSettleRefusedException extends RuntimeException {

    public OwnClaimSettleRefusedException(String user) {
        super("A claim opened by " + user + " must be settled by someone else");
    }
}
