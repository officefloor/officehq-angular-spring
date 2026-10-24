package net.officefloor.hq.app.claim;

import java.util.Set;

/** Where a claim is in its life: added as a draft, opened, then settled; a draft or open claim can
 * be voided. A settled or voided claim cannot change status. */
public enum ClaimStatus {
    DRAFT, OPEN, SETTLED, VOID;

    /** Whether a claim in this status counts toward what its person has claimed and is due. */
    public boolean counts() {
        return this == OPEN || this == SETTLED;
    }

    /** Whether a claim in this status may move to {@code next}. */
    public boolean canBecome(ClaimStatus next) {
        return switch (this) {
            case DRAFT -> Set.of(OPEN, VOID).contains(next);
            case OPEN -> Set.of(SETTLED, VOID).contains(next);
            case SETTLED, VOID -> false;
        };
    }
}
