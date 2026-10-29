package net.officefloor.hq.app.claim;

/**
 * A claim's status: it starts as DRAFT, is opened, then settled; a clerk's settling above the clerk limit makes it
 * PENDING until a steward approves it (SETTLED) or rejects it (OPEN again); a DRAFT, OPEN or PENDING claim can be
 * voided, and a SETTLED one by a steward within the days after its settlement. A DRAFT claim can be DELETED, and on
 * the sheet's today it was deleted restored to a DRAFT again; a DELETED claim counts nowhere. A DRAFT whose due is
 * above the review threshold in force on its date goes to REVIEW when opened, until a steward reviews it (OPEN); a
 * REVIEW claim counts nowhere and can be voided.
 */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID, DELETED, REVIEW;

    /** Whether a claim in this status may move to {@code next}. */
    public boolean canMoveTo(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT || this == PENDING || this == REVIEW;
            case PENDING -> this == OPEN;
            case SETTLED -> this == OPEN || this == PENDING;
            case VOID -> this == DRAFT || this == OPEN || this == PENDING || this == SETTLED || this == REVIEW;
            case DRAFT -> this == DELETED;
            case DELETED -> this == DRAFT;
            case REVIEW -> this == DRAFT;
        };
    }

    /** Whether a claim in this status counts toward what is claimed and due: only open, pending and settled ones do. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether a claim in this status is open: OPEN or PENDING. An open claim goes overdue and bears the late charge. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }
}
