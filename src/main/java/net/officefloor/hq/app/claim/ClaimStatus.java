package net.officefloor.hq.app.claim;

/**
 * A claim's status: DRAFT when added; a DRAFT claim may be opened, an OPEN one settled, and a DRAFT,
 * OPEN or PENDING one voided. An OPEN claim a clerk settles above the clerk limit goes PENDING, which
 * a steward approves (SETTLED) or rejects (back to OPEN). A steward may void a SETTLED claim within
 * {@link #VOID_SETTLED_DAYS} days of its settlement; VOID is final. Only OPEN, PENDING and SETTLED
 * claims count.
 */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** The days after its settlement (by the sheet's today) a SETTLED claim may still be voided. */
    public static final int VOID_SETTLED_DAYS = 7;

    /** Whether a claim may move from this status to {@code next}. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED, PENDING -> this == OPEN;
            case VOID -> this == DRAFT || this == OPEN || this == PENDING || this == SETTLED;
            case DRAFT -> false;
        };
    }

    /** Whether a claim in this status counts toward what is claimed and due. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether a claim in this status is open: OPEN, or PENDING a steward's approval. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }
}
