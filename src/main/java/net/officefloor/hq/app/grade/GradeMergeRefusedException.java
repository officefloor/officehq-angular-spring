package net.officefloor.hq.app.grade;

/** A grade merge by anyone but a steward, of a grade into itself, or of something that is not a grade. */
public class GradeMergeRefusedException extends RuntimeException {

    public GradeMergeRefusedException(String message) {
        super(message);
    }
}
