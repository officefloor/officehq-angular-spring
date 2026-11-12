package net.officefloor.hq.app.claim;

import java.util.Set;

/** A claim's status: DRAFT when added; it is opened, then settled; a DRAFT or OPEN claim can be voided. */
public enum ClaimStatus {
    DRAFT, OPEN, SETTLED, VOID;

    /** Whether a claim in this status can move to the given one. */
    public boolean canBecome(ClaimStatus to) {
        return switch (this) {
            case DRAFT -> Set.of(OPEN, VOID).contains(to);
            case OPEN -> Set.of(SETTLED, VOID).contains(to);
            case SETTLED, VOID -> false;
        };
    }

    /** Whether a claim in this status counts toward claimed, due-total and statements. */
    public boolean counts() {
        return this == OPEN || this == SETTLED;
    }
}
