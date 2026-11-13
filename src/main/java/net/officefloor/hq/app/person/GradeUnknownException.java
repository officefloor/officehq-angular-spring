package net.officefloor.hq.app.person;

/** A grade chosen for a person that is not one of the sheet's grades. */
public class GradeUnknownException extends RuntimeException {

    public GradeUnknownException(String grade) {
        super("Grade " + grade + " is not a grade of this sheet; choose another grade.");
    }
}
