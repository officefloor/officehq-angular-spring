package net.officefloor.hq.app.claim;

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; an OPEN claim a clerk settles above
 *  the clerk limit is PENDING until a steward approves it (SETTLED) or rejects it (OPEN again); a
 *  DRAFT, OPEN or PENDING claim can be VOID, and a steward may void a SETTLED one within 7 days of
 *  its settlement. A DRAFT can be DELETED, counting nowhere, and restored to a DRAFT on the sheet's
 *  today it was deleted. A claim whose due is above the review threshold goes to REVIEW when opened,
 *  counting nowhere until a steward reviews it (OPEN); a REVIEW claim can be VOID. */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID, DELETED, REVIEW;

    /** Whether the claim counts toward what is claimed and due. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether the claim is open: OPEN, or PENDING an approval. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }

    /** Whether a claim in this status may be moved to another person: a SETTLED or VOID one cannot. */
    public boolean canReassign() {
        return this == DRAFT || this == OPEN || this == PENDING;
    }

    /** Whether a claim in this status may be opened, settled or voided to the given one; a PENDING
     *  claim leaves PENDING only by being approved, rejected or voided. */
    public boolean canMoveTo(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN || this == PENDING || this == REVIEW;
            case PENDING, DRAFT, DELETED, REVIEW -> false;
        };
    }
}
