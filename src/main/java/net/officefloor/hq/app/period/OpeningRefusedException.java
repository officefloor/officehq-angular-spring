package net.officefloor.hq.app.period;

/** A pasted opening balance line naming no person or period on the sheet, or with an amount below 0.00
 *  or more than two decimals: nothing of the paste is added. */
public class OpeningRefusedException extends RuntimeException {

    public OpeningRefusedException(String message) {
        super(message);
    }
}
