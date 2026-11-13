package net.officefloor.hq.app.person;

/** A hold that may not be put on: its to-date is before its from-date. */
public class HoldRefusedException extends RuntimeException {

    public HoldRefusedException(String message) {
        super(message);
    }
}
