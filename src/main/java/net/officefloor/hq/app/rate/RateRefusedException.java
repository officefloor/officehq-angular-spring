package net.officefloor.hq.app.rate;

/** A rate that cannot be set: its to-date is before its from-date. */
public class RateRefusedException extends RuntimeException {

    public RateRefusedException(String message) {
        super(message);
    }
}
