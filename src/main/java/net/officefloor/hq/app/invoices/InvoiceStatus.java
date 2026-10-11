package net.officefloor.hq.app.invoices;

/**
 * Where an invoice is in its life: still being written, or sent to the client. A sent invoice is
 * part paid while some of it has been paid and some is still due, and paid once nothing is due;
 * those two follow from its payments and are never stored. A sent invoice with something still due
 * after its due date is overdue, part paid or not; that follows from the date and is never stored.
 */
public enum InvoiceStatus {
    DRAFT, SENT, PART_PAID, PAID, OVERDUE
}
