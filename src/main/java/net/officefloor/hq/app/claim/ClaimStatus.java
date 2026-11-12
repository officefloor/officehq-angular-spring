package net.officefloor.hq.app.claim;

import java.util.Set;

/** A claim's status: DRAFT when added; it is opened, then settled. A clerk settling a due above the clerk
 *  limit puts it PENDING instead, until a steward approves it (SETTLED) or rejects it (back to OPEN). A
 *  DRAFT, OPEN or PENDING claim can be voided, and a SETTLED one by a steward within 7 days of settling. */
public enum ClaimStatus {
    DRAFT, OPEN, PENDING, SETTLED, VOID;

    /** Whether a claim in this status can move to the given one by opening, settling or voiding it;
     *  approving and rejecting move a PENDING claim on. */
    public boolean canBecome(ClaimStatus to) {
        return switch (this) {
            case DRAFT -> Set.of(OPEN, VOID).contains(to);
            case OPEN -> Set.of(SETTLED, VOID).contains(to);
            case PENDING -> to == VOID;
            case SETTLED -> to == VOID;
            case VOID -> false;
        };
    }

    /** Whether a claim in this status can be reassigned to another person: DRAFT, OPEN or PENDING. */
    public boolean canMove() {
        return this == DRAFT || this == OPEN || this == PENDING;
    }

    /** Whether a claim in this status counts toward claimed, due-total and statements. */
    public boolean counts() {
        return this == OPEN || this == PENDING || this == SETTLED;
    }

    /** Whether a claim in this status is still open: OPEN or PENDING. It goes overdue and bears the late
     *  charge, and a hold on its person takes it out of the live totals. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }
}
