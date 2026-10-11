package net.officefloor.hq.app.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.TaxRate;
import net.officefloor.hq.app.Today;
import net.officefloor.hq.app.clients.Client;
import net.officefloor.hq.app.clients.ClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists a client's invoices, opens one, starts new drafts, duplicates one, adds lines to them, gives them a discount,
 * sends them, voids them and records payments against them.
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

    /** Every invoice of every client, with its lines. */
    @Transactional(readOnly = true)
    public List<Invoice> all() {
        return invoices.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Invoice> find(Long id) {
        return invoices.findWithLinesById(id);
    }

    /** An empty draft for the client, issued today and due on the client's terms; empty if no such client. */
    @Transactional
    public Optional<Invoice> newDraft(Long clientId) {
        if (!clients.existsById(clientId)) {
            return Optional.empty();
        }
        LocalDate issued = today.date();
        return Optional.of(invoices.save(Invoice.draft(clientId, issued, dueDateFor(clientId, issued))));
    }

    /**
     * A new draft for the invoice's client with copies of its lines and the same invoice discount,
     * issued today and due as any new invoice is, and records it in the audit; empty if no such
     * invoice. Payments are not copied.
     */
    @Transactional
    public Optional<Invoice> duplicate(Long invoiceId) {
        return invoices.findWithLinesById(invoiceId).map(original -> {
            LocalDate issued = today.date();
            Invoice copy = invoices.saveAndFlush(original.duplicate(issued, dueDateFor(original.getClientId(), issued)));
            audit.record("INVOICE_DUPLICATED from=" + InvoiceNumber.of(original.getId())
                    + " to=" + InvoiceNumber.of(copy.getId()));
            return copy;
        });
    }

    /**
     * An invoice for a client with payment terms is due that many days after it is issued; otherwise
     * as {@link #dueDateFor(LocalDate)}.
     */
    private LocalDate dueDateFor(Long clientId, LocalDate issued) {
        return clients.findById(clientId)
                .map(Client::getTermsDays)
                .map(days -> issued.plusDays(days))
                .orElseGet(() -> dueDateFor(issued));
    }

    /** A new invoice is due on the last day of the month after the month it is issued in. */
    static LocalDate dueDateFor(LocalDate issued) {
        LocalDate nextMonth = issued.plusMonths(1);
        return nextMonth.withDayOfMonth(nextMonth.lengthOfMonth());
    }

    /**
     * Adds a line to an invoice; empty if no such invoice. The line has already been checked. A sent
     * invoice with no payments goes back to draft; one with a payment refuses it.
     */
    @Transactional
    public Optional<Invoice> addLine(Long invoiceId, String description, BigDecimal qty, BigDecimal unitPrice,
            boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        found.ifPresent(invoice -> {
            boolean reopened = reopenForChange(invoice, "A paid invoice cannot take new lines.");
            invoice.addLine(new InvoiceLine(description, qty, unitPrice, taxExempt, discountPct, taxRate));
            invoices.saveAndFlush(invoice);
            if (reopened) {
                audit.record("INVOICE_REOPENED id=" + invoice.getId());
            }
        });
        return found;
    }

    /**
     * Removes a line from an invoice; empty if no such invoice or line. Refused when what is left would
     * be less than a fixed discount. A sent invoice with no payments goes back to draft; one with a
     * payment refuses it.
     */
    @Transactional
    public Optional<Invoice> removeLine(Long invoiceId, Long lineId) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        if (found.isEmpty()) {
            return found;
        }
        Invoice invoice = found.get();
        if (!invoice.hasLine(lineId)) {
            return Optional.empty();
        }
        boolean reopened = reopenForChange(invoice, "A paid invoice cannot have lines removed.");
        invoice.removeLine(lineId);
        invoices.saveAndFlush(invoice);
        if (reopened) {
            audit.record("INVOICE_REOPENED id=" + invoice.getId());
        }
        return found;
    }

    /**
     * Gives an invoice a percentage discount, or a fixed amount off when one is given; the one given
     * clears the other, and the cap (null for none) limits everything taken off, line discounts included. Empty if no such invoice. The values have already been checked; a fixed amount
     * over the subtotal is refused. A sent invoice with no payments goes back to draft; one with a
     * payment refuses it.
     */
    @Transactional
    public Optional<Invoice> setDiscount(Long invoiceId, BigDecimal discountPct, BigDecimal discountAmount,
            BigDecimal discountCap) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        found.ifPresent(invoice -> {
            boolean reopened = reopenForChange(invoice, "A paid invoice cannot be given a discount.");
            if (discountAmount != null) {
                invoice.setDiscountAmount(discountAmount);
            } else {
                invoice.setDiscountPct(discountPct);
            }
            invoice.setDiscountCap(discountCap);
            invoices.saveAndFlush(invoice);
            if (reopened) {
                audit.record("INVOICE_REOPENED id=" + invoice.getId());
            }
        });
        return found;
    }

    /**
     * Readies the invoice for a change: a draft stays as it is; a sent invoice with no payments goes
     * back to draft, to be sent again; an invoice with any payment is refused with the message.
     * Whether it went back to draft.
     */
    private boolean reopenForChange(Invoice invoice, String refusal) {
        if (invoice.isDraft()) {
            return false;
        }
        if (invoice.isVoid()) {
            throw new InvoiceRejectedException("A void invoice cannot be changed.");
        }
        if (!payments.findForInvoice(invoice.getId()).isEmpty()) {
            throw new InvoiceRejectedException(refusal);
        }
        invoice.reopen();
        return true;
    }

    /**
     * Sends a draft invoice, issuing it today and making it due on the client's terms, and records it in the audit; empty if
     * no such invoice. An invoice already sent, or with no lines, is refused.
     */
    @Transactional
    public Optional<Invoice> send(Long invoiceId) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException("A void invoice cannot be sent.");
            }
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("This invoice has already been sent.");
            }
            if (invoice.getLines().isEmpty()) {
                throw new InvoiceRejectedException("Add at least one line before sending the invoice.");
            }
            markSent(invoice);
        });
        return found;
    }

    /**
     * Sends each of the client's drafts that has at least one line, lowest id first, as {@link #send}
     * would; drafts with no lines stay drafts.
     */
    @Transactional
    public void sendAllDrafts(Long clientId) {
        invoices.findByClientIdOrderByIdAsc(clientId).stream()
                .filter(invoice -> invoice.isDraft() && !invoice.getLines().isEmpty())
                .forEach(this::markSent);
    }

    /** Issues the draft today, due on the client's terms, and records it in the audit. */
    private void markSent(Invoice invoice) {
        LocalDate issued = today.date();
        invoice.markSent(issued, dueDateFor(invoice.getClientId(), issued));
        invoices.saveAndFlush(invoice);
        audit.record("INVOICE_SENT id=" + invoice.getId() + " number=" + InvoiceNumber.of(invoice.getId())
                + " total=" + invoice.total(taxRate.percent()).toPlainString());
    }

    /**
     * Voids a sent invoice with no payments, and records it in the audit; empty if no such invoice. A
     * draft, an invoice already void, or one with any payment is refused.
     */
    @Transactional
    public Optional<Invoice> voidInvoice(Long invoiceId) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException("This invoice is already void.");
            }
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("A draft cannot be voided; only a sent invoice can.");
            }
            if (!payments.findForInvoice(invoice.getId()).isEmpty()) {
                throw new InvoiceRejectedException("An invoice with payments cannot be voided.");
            }
            invoice.markVoid();
            invoices.saveAndFlush(invoice);
            audit.record("INVOICE_VOIDED id=" + invoice.getId() + " number=" + InvoiceNumber.of(invoice.getId()));
        });
        return found;
    }

    /** The payments made to the invoice, oldest first, with their allocations. */
    @Transactional(readOnly = true)
    public List<Payment> payments(Long invoiceId) {
        return payments.findForInvoice(invoiceId);
    }

    /** The date each fully paid invoice was paid (see {@link Invoice#paidOn}); an invoice not fully paid is left out. */
    @Transactional(readOnly = true)
    public Map<Long, LocalDate> paidOn(List<Invoice> list) {
        Map<Long, LocalDate> paidOn = new HashMap<>();
        if (list.isEmpty()) {
            return paidOn;
        }
        List<Payment> found = payments.findForInvoices(list.stream().map(Invoice::getId).toList());
        BigDecimal rate = taxRate.percent();
        for (Invoice i : list) {
            LocalDate on = i.paidOn(found, rate);
            if (on != null) {
                paidOn.put(i.getId(), on);
            }
        }
        return paidOn;
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
     * checked; a draft, an invoice already paid, an amount over what is due, or a date after today
     * or before the invoice's issue date is refused. The reference (null when none was quoted) has
     * already been checked; one that belongs to a different invoice is refused.
     */
    @Transactional
    public Optional<Invoice> recordPayment(Long invoiceId, BigDecimal amount, LocalDate date, String reference) {
        Optional<Invoice> found = invoices.findWithLinesById(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("Send the invoice before recording a payment.");
            }
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException("A void invoice takes no payments.");
            }
            BigDecimal due = invoice.amountDue(paid(List.of(invoice)).get(invoice.getId()), taxRate.percent());
            if (due.signum() <= 0) {
                throw new InvoiceRejectedException("This invoice has already been paid.");
            }
            if (amount.compareTo(due) > 0) {
                throw new InvoiceRejectedException("The payment is more than the $" + due.toPlainString() + " due.");
            }
            LocalDate now = today.date();
            if (date.isAfter(now)) {
                throw new InvoiceRejectedException("The payment date cannot be after today (" + now + ").");
            }
            if (invoice.getIssueDate() != null && date.isBefore(invoice.getIssueDate())) {
                throw new InvoiceRejectedException(
                        "The payment date cannot be before the invoice's issue date (" + invoice.getIssueDate() + ").");
            }
            if (reference != null && !reference.equals(PaymentReference.of(invoice.getId()))) {
                throw new InvoiceRejectedException(PaymentReference.grouped(reference)
                        + " is the reference of a different invoice; this invoice's is "
                        + PaymentReference.grouped(PaymentReference.of(invoice.getId())) + ".");
            }
            Payment payment = payments.saveAndFlush(Payment.against(invoice, amount, date, reference));
            audit.record("PAYMENT_RECORDED id=" + payment.getId() + " invoice=" + invoice.getId() + " amount="
                    + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        });
        return found;
    }

    /**
     * Records one payment of the amount on the date against the client's unpaid sent invoices, earliest
     * due date first (lower id first on the same due date) so overdue invoices are cleared first,
     * paying each off in full before the next.
     * A payment more than everything the client owes is refused; otherwise it is checked and recorded
     * as a split payment over the invoices it reaches.
     * A reference (null when none, already checked) instead sends the whole payment to the client's
     * invoice with that reference; one that is not the reference of any of the client's invoices is refused.
     */
    @Transactional
    public Payment recordAutoPayment(Long clientId, BigDecimal amount, LocalDate date, String reference) {
        if (reference != null) {
            Invoice named = invoices.findByClientIdOrderByIdAsc(clientId).stream()
                    .filter(i -> reference.equals(PaymentReference.of(i.getId())))
                    .findFirst()
                    .orElseThrow(() -> new InvoiceRejectedException(PaymentReference.grouped(reference)
                            + " is not the reference of any of this client's invoices."));
            return recordSplitPayment(clientId, amount, date, reference,
                    List.of(new PaymentAllocation(named.getId(), amount)));
        }
        List<Invoice> sent = invoices.findByClientIdOrderByIdAsc(clientId).stream()
                .filter(i -> !i.isDraft() && !i.isVoid())
                .sorted(Comparator.comparing(Invoice::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Invoice::getId))
                .toList();
        Map<Long, BigDecimal> paid = paid(sent);
        BigDecimal owed = BigDecimal.ZERO;
        BigDecimal left = amount;
        List<PaymentAllocation> parts = new ArrayList<>();
        for (Invoice invoice : sent) {
            BigDecimal due = invoice.amountDue(paid.get(invoice.getId()), taxRate.percent());
            if (due.signum() <= 0) {
                continue;
            }
            owed = owed.add(due);
            if (left.signum() > 0) {
                BigDecimal part = left.min(due);
                parts.add(new PaymentAllocation(invoice.getId(), part));
                left = left.subtract(part);
            }
        }
        if (owed.signum() <= 0) {
            throw new InvoiceRejectedException("The client has no unpaid invoices to pay.");
        }
        if (left.signum() > 0) {
            throw new InvoiceRejectedException("The payment is more than the $" + owed.setScale(2).toPlainString()
                    + " the client owes.");
        }
        return recordSplitPayment(clientId, amount, date, reference, parts);
    }

    /**
     * Records one payment of the amount on the date spread over several of the client's invoices, and
     * records each part in the audit. The amounts have already been checked to be more than zero; they
     * must add up exactly to the payment, and each must go to a different sent invoice of the client
     * and be no more than what it still has due. The date must be no later than today nor before any
     * of the invoices' issue dates. The reference (null when none) has already been checked; it must
     * be the reference of one of the invoices.
     */
    @Transactional
    public Payment recordSplitPayment(Long clientId, BigDecimal amount, LocalDate date, String reference,
            List<PaymentAllocation> parts) {
        if (parts.isEmpty()) {
            throw new InvoiceRejectedException("Enter how much of the payment goes to each invoice.");
        }
        BigDecimal sum = parts.stream().map(PaymentAllocation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(amount) != 0) {
            throw new InvoiceRejectedException("The amounts for the invoices add up to $"
                    + sum.setScale(2).toPlainString() + ", not the $" + amount.setScale(2).toPlainString() + " paid.");
        }
        LocalDate now = today.date();
        if (date.isAfter(now)) {
            throw new InvoiceRejectedException("The payment date cannot be after today (" + now + ").");
        }
        Set<Long> seen = new HashSet<>();
        List<Invoice> spreadOver = new ArrayList<>();
        for (PaymentAllocation part : parts) {
            if (!seen.add(part.getInvoiceId())) {
                throw new InvoiceRejectedException("Each invoice can take only one part of the payment.");
            }
            Invoice invoice = invoices.findWithLinesById(part.getInvoiceId())
                    .filter(i -> i.getClientId().equals(clientId))
                    .orElseThrow(() -> new InvoiceRejectedException("A part of the payment goes to an invoice of another client."));
            String number = InvoiceNumber.of(invoice.getId());
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("Send " + number + " before recording a payment against it.");
            }
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException(number + " is void and takes no payments.");
            }
            BigDecimal due = invoice.amountDue(paid(List.of(invoice)).get(invoice.getId()), taxRate.percent());
            if (part.getAmount().compareTo(due) > 0) {
                throw new InvoiceRejectedException("The $" + part.getAmount().setScale(2).toPlainString() + " for "
                        + number + " is more than the $" + due.toPlainString() + " it has due.");
            }
            if (invoice.getIssueDate() != null && date.isBefore(invoice.getIssueDate())) {
                throw new InvoiceRejectedException("The payment date cannot be before the issue date of " + number
                        + " (" + invoice.getIssueDate() + ").");
            }
            spreadOver.add(invoice);
        }
        if (reference != null && spreadOver.stream().noneMatch(i -> reference.equals(PaymentReference.of(i.getId())))) {
            throw new InvoiceRejectedException(PaymentReference.grouped(reference)
                    + " is not the reference of any of the invoices being paid.");
        }
        Payment payment = payments.saveAndFlush(Payment.split(clientId, amount, date, reference, parts));
        for (PaymentAllocation part : parts) {
            audit.record("PAYMENT_RECORDED id=" + payment.getId() + " invoice=" + part.getInvoiceId() + " amount="
                    + part.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        }
        return payment;
    }

    /**
     * Reverses the payment (a bounced cheque), undoing it on every invoice it was applied to, and
     * records it in the audit; empty if no such payment. A payment already reversed is refused.
     */
    @Transactional
    public Optional<Payment> reversePayment(Long paymentId) {
        Optional<Payment> found = payments.findById(paymentId);
        found.ifPresent(payment -> {
            if (payment.isReversed()) {
                throw new InvoiceRejectedException("This payment has already been reversed.");
            }
            payment.reverse();
            payments.saveAndFlush(payment);
            audit.record("PAYMENT_REVERSED id=" + payment.getId() + " amount="
                    + payment.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        });
        return found;
    }
}
