package net.officefloor.hq.app.claim;

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a clerk settling one whose due is
 *  above the clerk limit sends it to PENDING instead, which a steward approves (SETTLED) or rejects
 *  (back to OPEN). A DRAFT, OPEN or PENDING claim may be VOID. */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether a claim in this status may move to the given status. */
    public boolean canMoveTo(ClaimStatus to) {
        return switch (to) {
            case OPEN -> this == DRAFT || this == PENDING;
            case PENDING -> this == OPEN;
            case SETTLED -> this == OPEN || this == PENDING;
            case VOID -> this == DRAFT || this == OPEN || this == PENDING;
            case DRAFT -> false;
        };
    }

    /** Whether a claim in this status is open: OPEN, or PENDING (handled like an OPEN one). */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }
}
