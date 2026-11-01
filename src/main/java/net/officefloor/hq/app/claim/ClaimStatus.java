package net.officefloor.hq.app.claim;

/**
 * A claim's status. It starts as DRAFT, is opened (OPEN) and then settled (SETTLED); a DRAFT,
 * OPEN or PENDING claim can be voided (VOID). A clerk's settling above the clerk limit makes it
 * PENDING instead, until a steward approves it (SETTLED) or rejects it (OPEN again). A steward
 * may void a SETTLED claim shortly after its settlement ({@link ClaimService}); VOID is final.
 */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /**
     * Whether a claim in this status may move to {@code next} by opening, settling or voiding it; a
     * PENDING claim leaves for SETTLED or OPEN only by a steward's approval or rejection.
     */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case PENDING, SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN || this == PENDING;
            case DRAFT -> false;
        };
    }

    /** Whether a claim in this status is open: OPEN, or PENDING, which is handled like it. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }

    /** Whether a claim in this status counts toward what is claimed and due. */
    public boolean counts() {
        return isOpen() || this == SETTLED;
    }
}
