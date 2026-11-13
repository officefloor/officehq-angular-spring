package net.officefloor.hq.app.claim;

/** Only a steward re-dates a claim. */
public class RedateForbiddenException extends RuntimeException {

    public RedateForbiddenException(String user) {
        super(user + " is not a steward: only a steward re-dates a claim");
    }
}
