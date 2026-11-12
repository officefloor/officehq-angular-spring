package net.officefloor.hq.app.person;

/** A move a person cannot make: up from the top of the list, or down from the bottom. */
public class MoveRefusedException extends RuntimeException {

    public MoveRefusedException(String message) {
        super(message);
    }
}
