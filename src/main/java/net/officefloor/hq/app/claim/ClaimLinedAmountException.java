package net.officefloor.hq.app.claim;

/** An edit of a claim with lines that types an amount other than its lines add up to. */
public class ClaimLinedAmountException extends RuntimeException {

    public static final String TYPE = "lined-amount";
}
