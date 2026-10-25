package net.officefloor.hq.app.claim;

import java.util.Set;

/** Where a claim is in its life: added as a draft, opened, then settled. A clerk's settle of a claim
 * whose due is above the clerk limit leaves it PENDING until a steward approves it (SETTLED) or rejects
 * it (back to OPEN). A draft, open or pending claim can be voided, and a settled one by a steward
 * within 7 days of its settlement. A voided claim cannot change status. A draft can be DELETED, and a
 * deleted claim made a DRAFT again (restored) on the same sheet-today it was deleted. A claim opened while its
 * due is above the review threshold in force on its date goes to REVIEW instead of OPEN, counting nowhere,
 * until a steward reviews it (OPEN); a claim in REVIEW can also be voided. */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID, DELETED, REVIEW;

    /** Whether a claim in this status counts toward what its person has claimed and is due. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether a claim in this status is still open: OPEN, or PENDING, which is handled like OPEN
     * (it goes overdue, bears the late charge). */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }

    /** Whether a claim in this status may move to {@code next}. */
    public boolean canBecome(ClaimStatus next) {
        return switch (this) {
            case DRAFT -> Set.of(OPEN, VOID, DELETED, REVIEW).contains(next);
            case REVIEW -> Set.of(OPEN, VOID).contains(next);
            case OPEN -> Set.of(PENDING, SETTLED, VOID).contains(next);
            case PENDING -> Set.of(OPEN, SETTLED, VOID).contains(next);
            case SETTLED -> next == VOID;
            case VOID -> false;
            case DELETED -> next == DRAFT;
        };
    }
}
