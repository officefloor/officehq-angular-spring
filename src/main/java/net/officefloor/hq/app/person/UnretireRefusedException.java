package net.officefloor.hq.app.person;

/** Only a retired person can be unretired. */
public class UnretireRefusedException extends RuntimeException {

    public UnretireRefusedException(String message) {
        super(message);
    }
}
