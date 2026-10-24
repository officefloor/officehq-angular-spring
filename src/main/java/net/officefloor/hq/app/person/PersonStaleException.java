package net.officefloor.hq.app.person;

/** Saving a person from an editor that loaded a version another user has since saved over. */
public class PersonStaleException extends RuntimeException {

    private final Person current;

    public PersonStaleException(Person current) {
        this.current = current;
    }

    /** The person as now stored. */
    public Person getCurrent() {
        return current;
    }
}
