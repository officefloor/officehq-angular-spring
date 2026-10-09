package net.officefloor.hq.app.invoice;

/**
 * Lifecycle stage of an invoice: DRAFT -> SENT -> PARTIAL -> PAID. Once sent, the stage is worked
 * out from the payments recorded against the invoice.
 */
public enum InvoiceStatus {
    DRAFT,
    SENT,
    PARTIAL,
    PAID;

    /** Whether payments can still be recorded against an invoice at this stage. */
    public boolean isOwing() {
        return this == SENT || this == PARTIAL;
    }
}
