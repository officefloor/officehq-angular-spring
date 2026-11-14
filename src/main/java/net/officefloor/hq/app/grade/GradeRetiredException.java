package net.officefloor.hq.app.grade;

/** A grade that cannot be retired (it is not a grade, or it is retired already), or a retired grade
 *  chosen for a person. */
public class GradeRetiredException extends RuntimeException {

    public GradeRetiredException(String message) {
        super(message);
    }
}
