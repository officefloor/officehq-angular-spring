package net.officefloor.hq.app.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Today;
import net.officefloor.hq.app.clients.ClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists a client's invoices, opens one, starts new drafts and adds lines to them. */
@Service
public class InvoiceService {

    /** A new invoice is due this many days after it is issued. */
    static final int PAYMENT_TERMS_DAYS = 30;

    private final InvoiceRepository invoices;
    private final ClientRepository clients;
    private final Today today;

    public InvoiceService(InvoiceRepository invoices, ClientRepository clients, Today today) {
        this.invoices = invoices;
        this.clients = clients;
        this.today = today;
    }

    @Transactional(readOnly = true)
    public List<Invoice> forClient(Long clientId) {
        return invoices.findByClientIdOrderByIdAsc(clientId);
    }

    @Transactional(readOnly = true)
    public Optional<Invoice> find(Long id) {
        return invoices.findWithLinesById(id);
    }

    /** An empty draft for the client, issued today and due 30 days later; empty if no such client. */
    @Transactional
    public Optional<Invoice> newDraft(Long clientId) {
        if (!clients.existsById(clientId)) {
            return Optional.empty();
        }
        LocalDate issued = today.date();
        return Optional.of(invoices.save(Invoice.draft(clientId, issued, issued.plusDays(PAYMENT_TERMS_DAYS))));
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
}
