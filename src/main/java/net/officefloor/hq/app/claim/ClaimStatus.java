package net.officefloor.hq.app.claim;

/**
 * A claim's status: it starts as DRAFT, is opened (OPEN) and then settled (SETTLED); a clerk settling
 * one above the clerk limit leaves it PENDING until a steward approves it (SETTLED) or rejects it (back to
 * OPEN). A DRAFT, OPEN or PENDING claim can be voided (VOID). Only OPEN, PENDING and SETTLED claims count
 * toward what is claimed.
 */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether the claim counts toward claimed, due-total and every total. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether the claim is still to be paid: OPEN or PENDING. */
    public boolean unpaid() {
        return this == OPEN || this == PENDING;
    }

    /**
     * Whether a claim in this status may be moved to the given one by opening, settling or voiding it; a
     * PENDING claim leaves PENDING only by being approved, rejected or voided.
     */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case PENDING, SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN || this == PENDING;
            case DRAFT -> false;
        };
    }
}
