package net.officefloor.hq.app.claim;

/** Only a steward sets a claim's due-by. */
public class DueBySetForbiddenException extends RuntimeException {

    public DueBySetForbiddenException(String user) {
        super(user + " is not a steward: only a steward sets a claim's due-by");
    }
}
