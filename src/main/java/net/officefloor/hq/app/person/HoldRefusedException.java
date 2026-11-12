package net.officefloor.hq.app.person;

/** A hold that cannot be put on a person: one ending before it starts. */
public class HoldRefusedException extends RuntimeException {

    public HoldRefusedException(String message) {
        super(message);
    }
}
