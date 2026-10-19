package net.officefloor.hq.app.claim;

/** A claim's status. Only OPEN and SETTLED claims count toward what is claimed and due. */
public enum ClaimStatus {
    DRAFT, OPEN, SETTLED, VOID;

    /** Whether a claim in this status may move to the given status. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN;
            case DRAFT -> false;
        };
    }
}
