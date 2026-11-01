package net.officefloor.hq.app.claim;

/** The person already has as many OPEN or PENDING claims in the claim's period as the period's cap allows. */
public class ClaimCapReachedException extends RuntimeException {

    public static final String TYPE = "cap-reached";
}
