package net.officefloor.hq.app.person;

/** Someone else saved the person after the editor loaded it; carries the person as it is now. */
public class PersonVersionConflictException extends RuntimeException {

    private final transient Person current;

    public PersonVersionConflictException(Person current) {
        super("Someone else has changed this person.");
        this.current = current;
    }

    public Person getCurrent() {
        return current;
    }
}
