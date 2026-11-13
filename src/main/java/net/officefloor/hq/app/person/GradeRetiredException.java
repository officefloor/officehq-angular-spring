package net.officefloor.hq.app.person;

/** A retired grade chosen for a person: by the form for a new person or in an edit, or by an import. */
public class GradeRetiredException extends RuntimeException {

    public GradeRetiredException(String grade) {
        super("Grade " + grade + " is retired; choose another grade.");
    }
}
