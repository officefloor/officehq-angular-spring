package net.officefloor.hq.app.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.TaxRate;
import net.officefloor.hq.app.Today;
import net.officefloor.hq.app.clients.ClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists a client's invoices, opens one, starts new drafts, adds lines to them, gives them a discount,
 * sends them and records payments against them.
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final ClientRepository clients;
    private final Today today;
    private final TaxRate taxRate;
    private final Audit audit;

    public InvoiceService(InvoiceRepository invoices, PaymentRepository payments, ClientRepository clients,
            Today today, TaxRate taxRate, Audit audit) {
        this.invoices = invoices;
        this.payments = payments;
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
    public Optional<Invoice> addLine(Long invoiceId, String description, BigDecimal qty, BigDecimal unitPrice,
            boolean taxExempt) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a draft invoice can take new lines.");
            }
            invoice.addLine(new InvoiceLine(description, qty, unitPrice, taxExempt));
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

    /** The payments made to the invoice, oldest first, with their allocations. */
    @Transactional(readOnly = true)
    public List<Payment> payments(Long invoiceId) {
        return payments.findForInvoice(invoiceId);
    }

    /** What has been paid to each of the invoices; an invoice paid nothing is paid zero. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> paid(List<Invoice> list) {
        Map<Long, BigDecimal> paid = new HashMap<>();
        list.forEach(i -> paid.put(i.getId(), BigDecimal.ZERO.setScale(2)));
        if (!list.isEmpty()) {
            for (Object[] row : payments.paidTo(list.stream().map(Invoice::getId).toList())) {
                paid.put((Long) row[0], ((BigDecimal) row[1]).setScale(2));
            }
        }
        return paid;
    }

    /**
     * Records a payment of the amount on the date against a sent invoice that still has something
     * due, and records it in the audit; empty if no such invoice. The amount has already been
     * checked; a draft, an invoice already paid, or an amount over what is due is refused.
     */
    @Transactional
    public Optional<Invoice> recordPayment(Long invoiceId, BigDecimal amount, LocalDate date) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("Send the invoice before recording a payment.");
            }
            BigDecimal due = invoice.amountDue(paid(List.of(invoice)).get(invoice.getId()), taxRate.percent());
            if (due.signum() <= 0) {
                throw new InvoiceRejectedException("This invoice has already been paid.");
            }
            if (amount.compareTo(due) > 0) {
                throw new InvoiceRejectedException("The payment is more than the $" + due.toPlainString() + " due.");
            }
            Payment payment = payments.saveAndFlush(Payment.against(invoice, amount, date));
            audit.record("PAYMENT_RECORDED id=" + payment.getId() + " invoice=" + invoice.getId() + " amount="
                    + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        });
        return found;
    }
}
