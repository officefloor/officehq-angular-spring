package net.officefloor.hq.app.claim;

/**
 * Where a claim is in its life: DRAFT, then OPEN, then SETTLED. A clerk settling an OPEN claim whose due is above the
 * clerk limit sends it to PENDING instead, which a steward approves (SETTLED) or rejects (back to OPEN). A DRAFT, OPEN
 * or PENDING claim may be VOID.
 */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether a claim in this status may move to the next one by opening, settling or voiding it. */
    public boolean canMoveTo(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED, PENDING -> this == OPEN;
            case VOID -> this == DRAFT || isOpen();
            case DRAFT -> false;
        };
    }

    /** Whether a claim in this status counts toward claimed and the totals. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether a claim in this status is handled as open: it goes overdue, bears the late charge, and a hold takes it out of the totals. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }
}
