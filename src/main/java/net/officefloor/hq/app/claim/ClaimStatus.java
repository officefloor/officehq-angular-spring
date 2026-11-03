package net.officefloor.hq.app.claim;

/**
 * A claim's status: DRAFT when added; opened (OPEN), then settled (SETTLED); a DRAFT or OPEN claim
 * can be voided (VOID). A SETTLED or VOID claim cannot change status.
 */
public enum ClaimStatus {
    DRAFT, OPEN, SETTLED, VOID;

    /** Whether a claim in this status may move to the given one. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN;
            case DRAFT -> false;
        };
    }

    /** Whether a claim in this status counts toward the totals: only OPEN and SETTLED do. */
    public boolean counts() {
        return this == OPEN || this == SETTLED;
    }
}
