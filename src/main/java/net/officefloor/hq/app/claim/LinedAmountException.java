package net.officefloor.hq.app.claim;

/** A claim with lines takes its amount from them: an edit typing another amount, or a line on a
 *  correction, is refused. */
public class LinedAmountException extends RuntimeException {

    public LinedAmountException(String message) {
        super(message);
    }
}
