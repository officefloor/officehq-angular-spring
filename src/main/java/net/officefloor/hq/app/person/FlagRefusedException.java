package net.officefloor.hq.app.person;

/** A flag that may not be put on: by anyone but a steward, or with a blank reason. */
public class FlagRefusedException extends RuntimeException {

    public FlagRefusedException(String message) {
        super(message);
    }
}
