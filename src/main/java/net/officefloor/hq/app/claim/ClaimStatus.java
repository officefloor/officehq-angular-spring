package net.officefloor.hq.app.claim;

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a clerk settling one whose due is
 *  above the clerk limit sends it to PENDING instead, which a steward approves (SETTLED) or rejects
 *  (back to OPEN). A DRAFT, OPEN or PENDING claim may be VOID, and a steward may void a SETTLED one
 *  within {@link #SETTLED_VOID_DAYS} days of its settlement. */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** How many days after its settlement (by the sheet's today) a steward may still void a SETTLED claim. */
    public static final int SETTLED_VOID_DAYS = 7;

    /** Whether a claim in this status may move to the given status. */
    public boolean canMoveTo(ClaimStatus to) {
        return switch (to) {
            case OPEN -> this == DRAFT || this == PENDING;
            case PENDING -> this == OPEN;
            case SETTLED -> this == OPEN || this == PENDING;
            case VOID -> this == DRAFT || this == OPEN || this == PENDING || this == SETTLED;
            case DRAFT -> false;
        };
    }

    /** Whether a claim in this status is open: OPEN, or PENDING (handled like an OPEN one). */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }
}
