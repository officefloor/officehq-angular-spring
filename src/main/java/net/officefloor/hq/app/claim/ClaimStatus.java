package net.officefloor.hq.app.claim;

/**
 * A claim's status: DRAFT when added; a DRAFT claim may be opened, an OPEN one settled, and a DRAFT
 * or OPEN one voided. SETTLED and VOID are final. Only OPEN and SETTLED claims count.
 */
public enum ClaimStatus {
    DRAFT, OPEN, SETTLED, VOID;

    /** Whether a claim may move from this status to {@code next}. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN;
            case DRAFT -> false;
        };
    }

    /** Whether a claim in this status counts toward what is claimed and due. */
    public boolean counts() {
        return this == OPEN || this == SETTLED;
    }
}
