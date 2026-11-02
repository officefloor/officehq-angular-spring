package net.officefloor.hq.app.claim;

/** Where a claim is in its life: it starts as a draft, is opened and then settled; a clerk's settle
 *  above the clerk limit leaves it pending until a steward approves it (settled) or rejects it (open
 *  again); a draft, open or pending claim may be voided, and a steward may void a settled claim
 *  within days of its settlement (see {@code ClaimService}). A voided claim cannot change status.
 *  A draft may be deleted, and restored to a draft on the same sheet-today; a deleted claim counts
 *  nowhere. */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID, DELETED;

    /** Whether a claim at this status may move to {@code to}: a pending claim moves to settled or
     *  back to open only by approval or rejection, which ask for it by {@link #PENDING} itself. */
    public boolean canBecome(ClaimStatus to) {
        return switch (to) {
            case OPEN -> this == DRAFT;
            case SETTLED, PENDING -> this == OPEN;
            case VOID -> this == DRAFT || isOpen();
            case DELETED -> this == DRAFT;
            case DRAFT -> false;
        };
    }

    /** Whether a claim at this status is open: open, or pending approval of its settle. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }

    /** Whether a claim at this status counts toward what its person has claimed. */
    public boolean counts() {
        return isOpen() || this == SETTLED;
    }
}
