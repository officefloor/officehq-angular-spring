package net.officefloor.hq.app.claim;

/** A claim's status. Only OPEN, PENDING and SETTLED claims count toward what is claimed and due. A PENDING claim
 * was settled by a clerk above the clerk limit and waits for a steward to approve it (SETTLED) or reject it
 * (OPEN again). */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether a claim in this status may be opened, settled (or sent PENDING) or voided to the given status. A
     * PENDING claim moves on only by a steward's approval or rejection, or by being voided. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case PENDING, SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN || this == PENDING;
            case DRAFT -> false;
        };
    }

    /** Whether a claim in this status is still open: OPEN, or PENDING a steward. Such a claim goes overdue, bears
     * the late charge, and a hold takes it out of the totals. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }
}
