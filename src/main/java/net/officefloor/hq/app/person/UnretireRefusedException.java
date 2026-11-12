package net.officefloor.hq.app.person;

/** An unretire that is not allowed: of someone not retired. */
public class UnretireRefusedException extends RuntimeException {

    public UnretireRefusedException(String message) {
        super(message);
    }
}
