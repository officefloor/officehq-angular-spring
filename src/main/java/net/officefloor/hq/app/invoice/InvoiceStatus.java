package net.officefloor.hq.app.invoice;

/**
 * Lifecycle stage of an invoice: DRAFT -> SENT -> PARTIAL -> PAID. Once sent, the stage is worked
 * out from the payments and credit notes recorded against the invoice. A sent invoice with nothing paid against it can
 * instead be cancelled, making it VOID: it stays on record but is no longer owed. A sent or part-paid invoice that
 * will never be paid can be written off as bad debt, making it WRITTEN_OFF: likewise kept on record but no longer owed.
 */
public enum InvoiceStatus {
    DRAFT,
    SENT,
    PARTIAL,
    PAID,
    VOID,
    WRITTEN_OFF;

    /** Whether payments can still be recorded against an invoice at this stage. */
    public boolean isOwing() {
        return this == SENT || this == PARTIAL;
    }

    /** Whether an invoice at this stage is closed without being paid (cancelled or written off), so nothing is owed. */
    public boolean isClosedUnpaid() {
        return this == VOID || this == WRITTEN_OFF;
    }
}
