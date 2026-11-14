package net.officefloor.hq.app.rate;

/** A rate that cannot be set: its to-date is before its from-date, or a pasted line is not one the
 *  rate form would set. */
public class RateRefusedException extends RuntimeException {

    public RateRefusedException(String message) {
        super(message);
    }
}
