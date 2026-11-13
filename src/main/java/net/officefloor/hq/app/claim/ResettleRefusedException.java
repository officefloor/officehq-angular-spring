package net.officefloor.hq.app.claim;

/** Only a steward re-settles a claim. */
public class ResettleRefusedException extends RuntimeException {

    public ResettleRefusedException(String user) {
        super(user + " is not a steward: only a steward re-settles a claim");
    }
}
