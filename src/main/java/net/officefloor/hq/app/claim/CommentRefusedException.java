package net.officefloor.hq.app.claim;

/** A comment that cannot be added to a claim: its text is blank. */
public class CommentRefusedException extends RuntimeException {

    public CommentRefusedException(String message) {
        super(message);
    }
}
