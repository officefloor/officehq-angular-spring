package net.officefloor.hq.app.access;

import java.math.BigDecimal;

/** A clerk cannot settle a claim whose due is above the sheet's clerk limit. */
public class OverClerkLimit extends RuntimeException {

    public OverClerkLimit(BigDecimal due, BigDecimal limit) {
        super("A clerk can settle a due of up to " + limit.toPlainString() + "; this one is "
                + due.toPlainString() + ", so a steward must settle it.");
    }
}
