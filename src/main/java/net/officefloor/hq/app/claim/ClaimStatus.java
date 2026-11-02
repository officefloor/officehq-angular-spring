package net.officefloor.hq.app.claim;

/** Where a claim is in its life: it starts as a draft, is opened and then settled; a draft or an
 *  open claim may be voided. A settled or voided claim cannot change status. */
public enum ClaimStatus {
    DRAFT, OPEN, SETTLED, VOID;

    /** Whether a claim at this status may move to {@code to}. */
    public boolean canBecome(ClaimStatus to) {
        return switch (to) {
            case OPEN -> this == DRAFT;
            case SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN;
            case DRAFT -> false;
        };
    }

    /** Whether a claim at this status counts toward what its person has claimed. */
    public boolean counts() {
        return this == OPEN || this == SETTLED;
    }
}
