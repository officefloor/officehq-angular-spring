package net.officefloor.hq.app.invoices;

/**
 * Where an invoice is in its life: still being written, or sent to the client. A sent invoice is
 * part paid while some of it has been paid and some is still due, and paid once nothing is due, or
 * paid late when the payment that cleared it (taking payments in date order) came after its due date;
 * those follow from its payments and are never stored. A sent invoice with something still due
 * more than a week (the grace period) after its due date is overdue, part paid or not; that follows from the date and is never stored.
 * A sent invoice with no payments can be voided: it is then void for good, owes nothing and takes no
 * payments or changes.
 * A credit note is a draft until it is issued, when it becomes CREDIT_NOTE for good. A sent invoice
 * whose issued credit notes leave nothing due, with nothing paid, is credited; that follows from its
 * credit notes and is never stored.
 * A sent invoice with retention still held back and nothing else due shows retention held instead of
 * paid; that follows from its retention and payments and is never stored.
 */
public enum InvoiceStatus {
    DRAFT, SENT, PART_PAID, PAID, PAID_LATE, OVERDUE, VOID, CREDIT_NOTE, CREDITED, RETENTION_HELD
}
