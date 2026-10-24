package net.officefloor.hq.app.claim;

import java.util.Set;

/** Where a claim is in its life: added as a draft, opened, then settled. A clerk's settle of a claim
 * whose due is above the clerk limit leaves it PENDING until a steward approves it (SETTLED) or rejects
 * it (back to OPEN). A draft, open or pending claim can be voided. A settled or voided claim cannot
 * change status. */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether a claim in this status counts toward what its person has claimed and is due. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether a claim in this status is still open: OPEN, or PENDING, which is handled like OPEN
     * (it goes overdue, bears the late charge and leaves the totals while its person is on hold). */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }

    /** Whether a claim in this status may move to {@code next}. */
    public boolean canBecome(ClaimStatus next) {
        return switch (this) {
            case DRAFT -> Set.of(OPEN, VOID).contains(next);
            case OPEN -> Set.of(PENDING, SETTLED, VOID).contains(next);
            case PENDING -> Set.of(OPEN, SETTLED, VOID).contains(next);
            case SETTLED, VOID -> false;
        };
    }
}
