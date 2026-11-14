package net.officefloor.hq.app.claim;

/** A claim cannot be opened: its person already has as many OPEN or PENDING claims as the sheet's
 *  maximum of open claims. */
public class MaxOpenException extends RuntimeException {

    public MaxOpenException(int max) {
        super("The person already has " + max + " open claims");
    }
}
