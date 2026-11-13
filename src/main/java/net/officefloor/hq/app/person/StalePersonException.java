package net.officefloor.hq.app.person;

/** The person was saved by someone else since the editor loaded it; carries the person as stored now. */
public class StalePersonException extends RuntimeException {

    private final Person current;

    public StalePersonException(Person current) {
        super("Person " + current.getId() + " was changed since it was loaded");
        this.current = current;
    }

    public Person getCurrent() {
        return current;
    }
}
