package net.officefloor.hq.app.claim;

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a DRAFT, OPEN or PENDING claim can be VOID, and a
 *  steward may void a SETTLED one within a few days of its settlement. A clerk
 *  settling an OPEN claim above the clerk limit makes it PENDING, which a steward approves (SETTLED) or rejects
 *  (OPEN again). */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether a claim in this status may move to {@code next} by a status change; approving or rejecting a
     *  PENDING claim is not a status change. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN || this == PENDING || this == SETTLED;
            case DRAFT, PENDING -> false;
        };
    }

    /** Whether a claim in this status counts toward what is claimed and due: OPEN, PENDING and SETTLED ones do. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether a claim in this status is still open: OPEN or PENDING, so it goes overdue and gains the late charge. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }
}
