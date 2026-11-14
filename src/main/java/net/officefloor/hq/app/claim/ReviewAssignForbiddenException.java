package net.officefloor.hq.app.claim;

/** Only a steward assigns a claim's review. */
public class ReviewAssignForbiddenException extends RuntimeException {

    public ReviewAssignForbiddenException(String user) {
        super(user + " is not a steward: only a steward assigns a claim's review");
    }
}
