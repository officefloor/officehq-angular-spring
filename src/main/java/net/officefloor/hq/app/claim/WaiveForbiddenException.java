package net.officefloor.hq.app.claim;

/** A waiver of a claim's late charge by a user who is not a steward. */
public class WaiveForbiddenException extends RuntimeException {

    public WaiveForbiddenException(String user) {
        super(user + " is not a steward: only a steward waives a late charge");
    }
}
