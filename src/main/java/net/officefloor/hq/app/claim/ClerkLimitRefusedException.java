package net.officefloor.hq.app.claim;

import java.math.BigDecimal;

/** A clerk cannot settle a claim whose due is above the sheet's clerk limit: a steward must. */
public class ClerkLimitRefusedException extends RuntimeException {

    public ClerkLimitRefusedException(String user, BigDecimal due, BigDecimal limit) {
        super("The clerk " + user + " cannot settle a due of " + due + " above the limit of " + limit);
    }
}
