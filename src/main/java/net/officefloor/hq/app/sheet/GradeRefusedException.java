package net.officefloor.hq.app.sheet;

/** A grade that cannot be retired: not one of the grades, or already retired. */
public class GradeRefusedException extends RuntimeException {

    public GradeRefusedException(String message) {
        super(message);
    }
}
