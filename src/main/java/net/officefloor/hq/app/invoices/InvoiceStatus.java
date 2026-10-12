package net.officefloor.hq.app.invoices;

/**
 * Where an invoice is in its life: still being written, or sent to the client. A sent invoice is
 * part paid while some of it has been paid and some is still due, and paid once nothing is due, or
 * paid late when the payment that cleared it (taking payments in date order) came after its due date;
 * those follow from its payments and are never stored. A sent invoice with something still due
 * more than its client's grace period (a week unless set otherwise; none for a HIGH payment risk client) after its due date is overdue, part paid or not; that follows from the date and is never stored.
 * A sent invoice with no payments can be voided: it is then void for good, owes nothing and takes no
 * payments or changes.
 * A credit note is a draft until it is issued, when it becomes CREDIT_NOTE for good. A sent invoice
 * whose issued credit notes leave nothing due, with nothing paid, is credited; that follows from its
 * credit notes and is never stored.
 * A sent invoice written off as a bad debt is written off for good: it owes nothing, earns no more
 * interest and takes no payments; that follows from the day it was written off.
 * A sent invoice in dispute is disputed until the dispute is resolved: it earns no interest, is not
 * overdue (a payment reaching it resolves the dispute); that follows from the days it was disputed and resolved.
 * A sent invoice billed by milestones with nothing owed now but milestones still to be reached is
 * awaiting a milestone; that follows from its milestones and payments and is never stored.
 * An overdue invoice with a promise to pay is promised until the promise's date, unless payments since
 * the promise reach the amount promised; it still earns late interest. That follows from the promise and its payments.
 */
public enum InvoiceStatus {
    DRAFT, SENT, PART_PAID, PAID, PAID_LATE, OVERDUE, VOID, CREDIT_NOTE, CREDITED, WRITTEN_OFF, DISPUTED, AWAITING_MILESTONE, PROMISED
}
