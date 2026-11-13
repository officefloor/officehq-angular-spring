package net.officefloor.hq.app.sheet;

/** A grade that cannot be added (not one to three capital letters, or already a grade) or retired (not one
 *  of the grades, or already retired), or a default grade not one of the grades. */
public class GradeRefusedException extends RuntimeException {

    public GradeRefusedException(String message) {
        super(message);
    }
}
