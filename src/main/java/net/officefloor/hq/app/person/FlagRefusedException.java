package net.officefloor.hq.app.person;

/** A flag that cannot be put on: by anyone but a steward, with a blank reason, or with no until-date. */
public class FlagRefusedException extends RuntimeException {

    public FlagRefusedException(String message) {
        super(message);
    }
}
