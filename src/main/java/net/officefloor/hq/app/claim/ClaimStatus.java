package net.officefloor.hq.app.claim;

/**
 * A claim's status. It starts as DRAFT, can be opened and then settled; a DRAFT or OPEN claim can
 * be voided. SETTLED and VOID are final. Only OPEN and SETTLED claims count toward the totals.
 */
public enum ClaimStatus {
    DRAFT, OPEN, SETTLED, VOID;

    /** Whether a claim in this status counts toward the totals. */
    public boolean counts() {
        return this == OPEN || this == SETTLED;
    }

    /** Whether a claim in this status may move to the given one. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN;
            case DRAFT -> false;
        };
    }
}
