package net.officefloor.hq.app.invoices;

/** A change an invoice cannot take; the message says why, in words the user can act on. */
public class InvoiceRejectedException extends RuntimeException {

    public InvoiceRejectedException(String message) {
        super(message);
    }
}
