package net.officefloor.hq.app.claim;

/**
 * A claim's status: it starts as DRAFT, is opened, then settled; a clerk's settling above the clerk limit makes it
 * PENDING until a steward approves it (SETTLED) or rejects it (OPEN again); a DRAFT, OPEN or PENDING claim can be
 * voided.
 */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether a claim in this status may move to {@code next}. */
    public boolean canMoveTo(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT || this == PENDING;
            case PENDING -> this == OPEN;
            case SETTLED -> this == OPEN || this == PENDING;
            case VOID -> this == DRAFT || this == OPEN || this == PENDING;
            case DRAFT -> false;
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
