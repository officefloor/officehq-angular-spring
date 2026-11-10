package net.officefloor.hq.app.claim;

/**
 * A claim's status: DRAFT when added; opened (OPEN), then settled (SETTLED). A clerk settling a
 * claim above the clerk limit makes it PENDING instead, until a steward approves it (SETTLED) or
 * rejects it (back to OPEN). A DRAFT, OPEN or PENDING claim can be voided (VOID), and a steward may
 * void a SETTLED one shortly after its settlement (see ClaimService). A VOID claim cannot change status.
 * A DRAFT claim can be deleted (DELETED), and restored to DRAFT on the sheet-today it was deleted
 * (see ClaimService); a DELETED claim counts nowhere and makes no other move.
 */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID, DELETED;

    /** Whether a claim in this status may move to the given one. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT || this == PENDING;
            case PENDING -> this == OPEN;
            case SETTLED -> this == OPEN || this == PENDING;
            case VOID -> this != VOID && this != DELETED;
            case DELETED -> this == DRAFT;
            case DRAFT -> this == DELETED;
        };
    }

    /** Whether a claim in this status counts toward the totals: OPEN, PENDING and SETTLED do. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether a claim in this status is open: OPEN or PENDING (it goes overdue, and a hold takes it out of the totals). */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }
}
