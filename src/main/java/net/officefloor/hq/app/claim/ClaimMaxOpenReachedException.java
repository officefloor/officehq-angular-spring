package net.officefloor.hq.app.claim;

/** The person already has as many OPEN or PENDING claims as the sheet's maximum of open claims allows. */
public class ClaimMaxOpenReachedException extends RuntimeException {

    public static final String TYPE = "max-open-reached";
}
