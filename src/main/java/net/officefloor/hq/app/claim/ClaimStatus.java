package net.officefloor.hq.app.claim;

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID. */
public enum ClaimStatus {
    DRAFT, OPEN, SETTLED, VOID;

    /** Whether the claim counts toward what is claimed and due. */
    public boolean counts() {
        return this == OPEN || this == SETTLED;
    }

    /** Whether a claim in this status may move to the given one. */
    public boolean canMoveTo(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN;
            case DRAFT -> false;
        };
    }
}
