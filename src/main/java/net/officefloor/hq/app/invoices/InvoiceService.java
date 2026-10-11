package net.officefloor.hq.app.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.TaxRate;
import net.officefloor.hq.app.Today;
import net.officefloor.hq.app.clients.ClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists a client's invoices, opens one, starts new drafts, adds lines to them, gives them a discount and sends them. */
@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final ClientRepository clients;
    private final Today today;
    private final TaxRate taxRate;
    private final Audit audit;

    public InvoiceService(InvoiceRepository invoices, ClientRepository clients, Today today, TaxRate taxRate,
            Audit audit) {
        this.invoices = invoices;
        this.clients = clients;
        this.today = today;
        this.taxRate = taxRate;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Invoice> forClient(Long clientId) {
        return invoices.findByClientIdOrderByIdAsc(clientId);
    }

    @Transactional(readOnly = true)
    public Optional<Invoice> find(Long id) {
        return invoices.findWithLinesById(id);
    }

    /** An empty draft for the client, issued today and due on the last day of the following month; empty if no such client. */
    @Transactional
    public Optional<Invoice> newDraft(Long clientId) {
        if (!clients.existsById(clientId)) {
            return Optional.empty();
        }
        LocalDate issued = today.date();
        return Optional.of(invoices.save(Invoice.draft(clientId, issued, dueDateFor(issued))));
    }

    /** A new invoice is due on the last day of the month after the month it is issued in. */
    static LocalDate dueDateFor(LocalDate issued) {
        LocalDate nextMonth = issued.plusMonths(1);
        return nextMonth.withDayOfMonth(nextMonth.lengthOfMonth());
    }

    /**
     * Adds a line to a draft invoice; empty if no such invoice. The line has already been checked;
     * a sent invoice refuses it.
     */
    @Transactional
    public Optional<Invoice> addLine(Long invoiceId, String description, BigDecimal qty, BigDecimal unitPrice) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a draft invoice can take new lines.");
            }
            invoice.addLine(new InvoiceLine(description, qty, unitPrice));
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Gives a draft invoice a percentage discount; empty if no such invoice. The percentage has
     * already been checked; a sent invoice refuses it.
     */
    @Transactional
    public Optional<Invoice> setDiscount(Long invoiceId, BigDecimal discountPct) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a draft invoice can be given a discount.");
            }
            invoice.setDiscountPct(discountPct);
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Sends a draft invoice, keeping its issue and due dates, and records it in the audit; empty if
     * no such invoice. An invoice already sent, or with no lines, is refused.
     */
    @Transactional
    public Optional<Invoice> send(Long invoiceId) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("This invoice has already been sent.");
            }
            if (invoice.getLines().isEmpty()) {
                throw new InvoiceRejectedException("Add at least one line before sending the invoice.");
            }
            invoice.markSent();
            invoices.saveAndFlush(invoice);
            audit.record("INVOICE_SENT id=" + invoice.getId() + " number=" + InvoiceNumber.of(invoice.getId())
                    + " total=" + invoice.total(taxRate.percent()).toPlainString());
        });
        return found;
    }
}
