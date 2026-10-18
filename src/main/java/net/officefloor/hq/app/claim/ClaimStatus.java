package net.officefloor.hq.app.claim;

/**
 * Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID. A
 * clerk settling an OPEN claim whose due is above the clerk limit makes it PENDING, which a steward
 * approves (SETTLED) or rejects (back to OPEN).
 */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether a claim in this status counts toward claimed and due: open, pending and settled ones do. */
    public boolean counts() {
        return isOpen() || this == SETTLED;
    }

    /** Whether a claim in this status counts as open: an open one, or one pending approval. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }

    /** Whether a claim in this status may move to {@code next}. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT || this == PENDING;
            case PENDING -> this == OPEN;
            case SETTLED -> this == OPEN || this == PENDING;
            case VOID -> this == DRAFT || this == OPEN;
            case DRAFT -> false;
        };
    }
}
