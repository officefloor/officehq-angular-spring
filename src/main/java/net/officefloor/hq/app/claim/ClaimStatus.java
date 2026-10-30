package net.officefloor.hq.app.claim;

/**
 * A claim's status: it starts as DRAFT, is opened (OPEN) and then settled (SETTLED); a DRAFT or
 * OPEN claim can be voided (VOID). Only OPEN and SETTLED claims count toward what is claimed.
 */
public enum ClaimStatus {
    DRAFT, OPEN, SETTLED, VOID;

    /** Whether the claim counts toward claimed, due-total and every total. */
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
