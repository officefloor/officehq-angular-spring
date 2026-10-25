package net.officefloor.hq.app.claim;

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID, and a steward
 *  may void a SETTLED one within 7 days of its settlement.
 *  A clerk's settling above the clerk limit leaves an OPEN claim PENDING, which a steward approves
 *  (SETTLED) or rejects (OPEN again); a PENDING claim is otherwise handled as an OPEN one. */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether the claim is open: OPEN, or PENDING, which is handled as open. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }

    /** Whether a claim in this status may move to `next` by opening, settling or voiding it; only a
     *  steward's approval or rejection moves a PENDING claim on. */
    public boolean canBecome(ClaimStatus next) {
        return switch (next) {
            case OPEN -> this == DRAFT;
            case SETTLED, PENDING -> this == OPEN;
            case VOID -> this == DRAFT || isOpen();
            case DRAFT -> false;
        };
    }
}
