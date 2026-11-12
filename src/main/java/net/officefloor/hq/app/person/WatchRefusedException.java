package net.officefloor.hq.app.person;

/** A watch that cannot be started: the user already watches the person. */
public class WatchRefusedException extends RuntimeException {

    public WatchRefusedException(String message) {
        super(message);
    }
}
