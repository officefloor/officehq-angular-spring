package net.officefloor.hq.app.claim;

/**
 * An external reference another claim on the sheet already carries, compared without regard to case.
 * Answered as a conflict whose problem detail is of {@link #TYPE}, telling it from other conflicts.
 */
public class DuplicateClaimRefException extends RuntimeException {

    /** The problem type of the answer. */
    public static final String TYPE = "duplicate-ref";
}
