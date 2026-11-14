package net.officefloor.hq.app.claim;

/** An assignment of a claim's review that is refused: the claim is not in REVIEW, or the user is blank. */
public class ReviewAssignRefusedException extends RuntimeException {

    public ReviewAssignRefusedException(String message) {
        super(message);
    }
}
