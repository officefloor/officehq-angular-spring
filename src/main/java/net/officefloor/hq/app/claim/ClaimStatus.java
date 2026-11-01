package net.officefloor.hq.app.claim;

/**
 * A claim's status. It starts as DRAFT, is opened (OPEN) and then settled (SETTLED); a DRAFT or
 * OPEN claim can be voided (VOID). SETTLED and VOID are final.
 */
public enum ClaimStatus {
    DRAFT, OPEN, SETTLED, VOID;

    /** Whether a claim in this status may move to {@code next}. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN;
            case DRAFT -> false;
        };
    }

    /** Whether a claim in this status counts toward what is claimed and due. */
    public boolean counts() {
        return this == OPEN || this == SETTLED;
    }

    /** Whether a claim in this status counts, for a person held or not: a held person's OPEN claims do not. */
    public boolean counts(boolean held) {
        return held ? this == SETTLED : counts();
    }
}
