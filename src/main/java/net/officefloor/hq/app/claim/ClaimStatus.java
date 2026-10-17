package net.officefloor.hq.app.claim;

/**
 * A claim's status. It starts as DRAFT, can be opened and then settled; a DRAFT or OPEN claim can
 * be voided. A clerk settling a claim whose due is above the clerk limit makes it PENDING instead:
 * a steward then approves it (SETTLED) or rejects it (back to OPEN). SETTLED and VOID are final.
 * Only OPEN, PENDING and SETTLED claims count toward the totals, a PENDING one as an open one.
 */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether a claim in this status counts toward the totals. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether a claim in this status counts as open: OPEN or PENDING. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }

    /**
     * Whether a claim in this status may be opened, settled or voided into the given one. Approving
     * and rejecting a PENDING claim are their own changes.
     */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case PENDING, SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN;
            case DRAFT -> false;
        };
    }
}
