package net.officefloor.hq.app.invoices;

import jakarta.persistence.EntityManager;
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
import net.officefloor.hq.app.ExchangeRates;
import net.officefloor.hq.app.LateInterestRate;
import net.officefloor.hq.app.TaxRate;
import net.officefloor.hq.app.Today;
import net.officefloor.hq.app.clients.Client;
import net.officefloor.hq.app.clients.ClientRepository;
import net.officefloor.hq.app.clients.Currency;
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
    private final EntityManager entityManager;
    private final ExchangeRates exchangeRates;
    private final LateInterestRate lateInterestRate;

    public InvoiceService(InvoiceRepository invoices, PaymentRepository payments, ClientRepository clients,
            Today today, TaxRate taxRate, Audit audit, EntityManager entityManager, ExchangeRates exchangeRates,
            LateInterestRate lateInterestRate) {
        this.lateInterestRate = lateInterestRate;
        this.exchangeRates = exchangeRates;
        this.entityManager = entityManager;
        this.invoices = invoices;
        this.payments = payments;
        this.clients = clients;
        this.today = today;
        this.taxRate = taxRate;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Invoice> forClient(Long clientId) {
        return loadForClient(clientId);
    }

    /** Every invoice of every client, with its lines. */
    @Transactional(readOnly = true)
    public List<Invoice> all() {
        return withInterest(invoices.findAllByOrderByIdAsc());
    }

    /** The invoice with its lines and the late interest it has been charged; empty if no such invoice. */
    private Optional<Invoice> load(Long id) {
        Optional<Invoice> found = invoices.findWithLinesById(id);
        found.ifPresent(invoice -> withInterest(List.of(invoice)));
        return found;
    }

    /** The client's invoices, lowest id first, with their lines and the late interest they have been charged. */
    private List<Invoice> loadForClient(Long clientId) {
        return withInterest(invoices.findByClientIdOrderByIdAsc(clientId));
    }

    /** Works out the late interest each of the invoices has been charged by today (see {@link Invoice#accrueLateInterest}). */
    private List<Invoice> withInterest(List<Invoice> list) {
        if (list.isEmpty()) {
            return list;
        }
        List<Payment> found = payments.findForInvoices(list.stream().map(Invoice::getId).toList());
        BigDecimal rate = taxRate.percent();
        BigDecimal annual = lateInterestRate.percent();
        LocalDate now = today.date();
        list.forEach(i -> i.accrueLateInterest(found, rate, annual, now));
        return list;
    }

    @Transactional(readOnly = true)
    public Optional<Invoice> find(Long id) {
        return load(id);
    }

    /**
     * An empty draft for the client, issued today, due on the client's terms and starting with the
     * client's standard discount; empty if no such client.
     */
    @Transactional
    public Optional<Invoice> newDraft(Long clientId) {
        return clients.findById(clientId).map(client -> {
            LocalDate issued = today.date();
            Invoice draft = Invoice.draft(clientId, client.isTaxExempt(), client.getCurrency(), issued, dueDateFor(clientId, issued));
            draft.setDiscountPct(client.getDiscountPct());
            return invoices.save(draft);
        });
    }

    /**
     * A new draft for the invoice's client with copies of its lines and the same invoice discount,
     * issued today and due as any new invoice is, and records it in the audit; empty if no such
     * invoice. Payments are not copied.
     */
    @Transactional
    public Optional<Invoice> duplicate(Long invoiceId) {
        return load(invoiceId).map(original -> {
            if (original.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be duplicated.");
            }
            LocalDate issued = today.date();
            Invoice copy = invoices.saveAndFlush(original.duplicate(issued, dueDateFor(original.getClientId(), issued)));
            audit.record("INVOICE_DUPLICATED from=" + InvoiceNumber.of(original.getId())
                    + " to=" + InvoiceNumber.of(copy.getId()));
            return copy;
        });
    }

    /**
     * A new, empty draft credit note against the sent invoice, issued today; empty if no such invoice.
     * A draft, a void invoice or a credit note is refused.
     */
    @Transactional
    public Optional<Invoice> creditNote(Long invoiceId) {
        return load(invoiceId).map(original -> {
            if (original.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot itself be credited.");
            }
            if (original.isDraft()) {
                throw new InvoiceRejectedException("Only a sent invoice can be credited.");
            }
            if (original.isVoid()) {
                throw new InvoiceRejectedException("A void invoice cannot be credited.");
            }
            return invoices.saveAndFlush(original.creditNote(today.date()));
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
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            requireWhole(invoice.currency(), unitPrice, "The unit price");
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
        Optional<Invoice> found = load(invoiceId);
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
     * Changes a line of an invoice; empty if no such invoice or line. The line has already been
     * checked. Refused when the subtotal would be less than a fixed discount. A sent invoice with no
     * payments goes back to draft; one with a payment refuses it.
     */
    @Transactional
    public Optional<Invoice> updateLine(Long invoiceId, Long lineId, String description, BigDecimal qty,
            BigDecimal unitPrice, boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate) {
        Optional<Invoice> found = load(invoiceId);
        if (found.isEmpty()) {
            return found;
        }
        Invoice invoice = found.get();
        if (!invoice.hasLine(lineId)) {
            return Optional.empty();
        }
        requireWhole(invoice.currency(), unitPrice, "The unit price");
        boolean reopened = reopenForChange(invoice, "A paid invoice cannot have lines changed.");
        invoice.updateLine(lineId, description, qty, unitPrice, taxExempt, discountPct, taxRate);
        invoices.saveAndFlush(invoice);
        if (reopened) {
            audit.record("INVOICE_REOPENED id=" + invoice.getId());
        }
        return found;
    }

    /**
     * Gives an invoice a percentage discount, or a fixed amount off when one is given; the one given
     * clears the other, and the cap (null for none) limits everything taken off, line discounts included. Empty if no such invoice. The values have already been checked; a fixed amount
     * over the subtotal is refused, as is a discount taking off more than a fifth of the subtotal
     * (after the cap) without a reason. A sent invoice with no payments goes back to draft; one with a
     * payment refuses it.
     */
    @Transactional
    public Optional<Invoice> setDiscount(Long invoiceId, BigDecimal discountPct, BigDecimal discountAmount,
            BigDecimal discountCap, String discountReason) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note has no discount.");
            }
            if (discountAmount != null) {
                requireWhole(invoice.currency(), discountAmount, "The discount amount");
            }
            if (discountCap != null) {
                requireWhole(invoice.currency(), discountCap, "The discount cap");
            }
            boolean reopened = reopenForChange(invoice, "A paid invoice cannot be given a discount.");
            if (discountAmount != null) {
                invoice.setDiscountAmount(discountAmount);
            } else {
                invoice.setDiscountPct(discountPct);
            }
            invoice.setDiscountCap(discountCap);
            invoice.setDiscountReason(discountReason);
            if (invoice.discountLacksReason()) {
                throw new InvoiceRejectedException(
                        "A discount of more than 20% of the subtotal needs a reason.");
            }
            invoices.saveAndFlush(invoice);
            if (reopened) {
                audit.record("INVOICE_REOPENED id=" + invoice.getId());
            }
        });
        return found;
    }

    /**
     * Sets a draft's due date by hand, kept when it is sent; empty if no such invoice. A date before
     * the issue date or more than {@link Invoice#MAX_DUE_DAYS} days after it is refused, as is any
     * invoice that is not a draft.
     */
    @Transactional
    public Optional<Invoice> setDueDate(Long invoiceId, LocalDate dueDate) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a draft's due date can be set.");
            }
            invoice.setDueDate(dueDate);
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Sets the percentage of a draft's amount before tax held back as retention; empty if no such invoice.
     * The percentage has already been checked; any invoice that is not a draft, or a credit note, is refused.
     */
    @Transactional
    public Optional<Invoice> setRetention(Long invoiceId, BigDecimal retentionPct) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note holds back no retention.");
            }
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("Retention can only be set on a draft.");
            }
            if (invoice.hasPlan() && retentionPct.signum() > 0) {
                throw new InvoiceRejectedException("An invoice spread over instalments cannot also hold back retention.");
            }
            invoice.setRetentionPct(retentionPct);
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Spreads a draft over the number of monthly instalments at the annual interest rate; empty if no
     * such invoice. Both have already been checked; any invoice that is not a draft, a credit note, or
     * one holding back retention is refused.
     */
    @Transactional
    public Optional<Invoice> setPlan(Long invoiceId, int instalments, BigDecimal instalmentRate) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be spread over instalments.");
            }
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a draft can be spread over instalments.");
            }
            if (invoice.getRetentionPct().signum() > 0) {
                throw new InvoiceRejectedException("An invoice holding back retention cannot also be spread over instalments.");
            }
            invoice.setPlan(instalments, instalmentRate);
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Releases the retention held back on a sent invoice today, adding it to the amount due (falling
     * due {@link Invoice#RETENTION_DUE_DAYS} days later), and records it in the audit; empty if no such
     * invoice. Refused while anything else is still due, or when there is no retention held to release.
     */
    @Transactional
    public Optional<Invoice> releaseRetention(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isDraft() || invoice.isVoid() || invoice.isCreditNote()) {
                throw new InvoiceRejectedException("Retention can only be released on a sent invoice.");
            }
            BigDecimal rate = taxRate.percent();
            BigDecimal held = invoice.retentionHeld(rate);
            if (held.signum() <= 0) {
                throw new InvoiceRejectedException("There is no retention held to release.");
            }
            BigDecimal due = invoice.amountDue(paid(List.of(invoice)).get(invoice.getId()), rate);
            if (due.signum() > 0) {
                throw new InvoiceRejectedException("The retention can only be released once nothing else is due; "
                        + invoice.currency().format(due) + " is still due.");
            }
            invoice.releaseRetention(today.date());
            invoices.saveAndFlush(invoice);
            audit.record("RETENTION_RELEASED id=" + invoice.getId() + " amount=" + held.toPlainString());
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
        if (invoice.isCreditNote()) {
            throw new InvoiceRejectedException("An issued credit note cannot be changed.");
        }
        if (invoice.hasCreditNotesIssued()) {
            throw new InvoiceRejectedException("An invoice with an issued credit note cannot be changed.");
        }
        if (!payments.findForInvoice(invoice.getId()).isEmpty()) {
            throw new InvoiceRejectedException(refusal);
        }
        invoice.reopen();
        return true;
    }

    /**
     * Sends a draft invoice, issuing it today and making it due on the client's terms (or keeping a due
     * date set by hand), and records it in the audit; empty if no such invoice. An invoice already
     * sent, with no lines, whose discount needs a reason but has none, or whose due date set by hand is
     * before today, is refused.
     */
    @Transactional
    public Optional<Invoice> send(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
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
            if (invoice.isCreditNote()) {
                String refusal = creditNoteRefusal(invoice);
                if (refusal != null) {
                    throw new InvoiceRejectedException(refusal);
                }
                issue(invoice);
                return;
            }
            if (invoice.discountLacksReason()) {
                throw new InvoiceRejectedException(
                        "The discount is more than 20% of the subtotal; give it a reason before sending the invoice.");
            }
            if (invoice.dueDatePassedBy(today.date())) {
                throw new InvoiceRejectedException("The due date (" + invoice.getDueDate()
                        + ") is already past; set a later due date before sending the invoice.");
            }
            markSent(invoice);
        });
        return found;
    }

    /**
     * Sends each of the client's drafts that has at least one line, lowest id first, as {@link #send}
     * would; drafts with no lines, whose discount needs a reason but has none, or whose due date set by
     * hand is already past, stay drafts.
     */
    @Transactional
    public void sendAllDrafts(Long clientId) {
        loadForClient(clientId).stream()
                .filter(invoice -> invoice.isDraft() && !invoice.getLines().isEmpty() && !invoice.discountLacksReason()
                        && !invoice.dueDatePassedBy(today.date()))
                .forEach(invoice -> {
                    if (!invoice.isCreditNote()) {
                        markSent(invoice);
                    } else if (creditNoteRefusal(invoice) == null) {
                        issue(invoice);
                    }
                });
    }

    /**
     * Why the draft credit note cannot be issued, or null when it can: its original must be a sent
     * invoice (not void).
     */
    private String creditNoteRefusal(Invoice note) {
        Invoice original = load(note.getCreditOf()).orElse(null);
        if (original == null || original.isDraft() || original.isVoid()) {
            return "The invoice this credits is not a sent invoice, so the credit note cannot be issued.";
        }
        return null;
    }

    /**
     * Issues the draft credit note today and records it in the audit: its grand total comes off what
     * its original still owes, then the retention it holds back; any rest is held on the client's
     * account as a credit payment dated today, also recorded in the audit.
     */
    private void issue(Invoice note) {
        BigDecimal rate = taxRate.percent();
        Invoice original = load(note.getCreditOf()).orElseThrow();
        // Read again what it has been credited: another credit note may just have been issued against it.
        entityManager.refresh(original);
        withInterest(List.of(original));
        BigDecimal total = note.total(rate);
        BigDecimal[] taken = original.creditTaken(total, paid(List.of(original)).get(original.getId()), rate);
        note.markIssued(today.date(), rate, taken[0], taken[1]);
        invoices.saveAndFlush(note);
        audit.record("CREDIT_NOTE_ISSUED id=" + note.getId() + " number=" + note.number() + " invoice="
                + note.getCreditOf() + " total=" + total.toPlainString());
        BigDecimal rest = total.subtract(taken[0]);
        if (rest.signum() > 0) {
            recordCreditHeld(payments.saveAndFlush(Payment.fromCreditNote(note, rest)));
        }
    }

    /**
     * Issues the draft today, due on the client's terms, and records it in the audit; then uses the
     * client's credit on it (see {@link #applyCredit}), unless it is spread over instalments, whose
     * schedule is dated from the new issue date.
     */
    private void markSent(Invoice invoice) {
        LocalDate issued = today.date();
        invoice.markSent(issued, dueDateFor(invoice.getClientId(), issued));
        invoices.saveAndFlush(invoice);
        audit.record("INVOICE_SENT id=" + invoice.getId() + " number=" + invoice.number()
                + " total=" + invoice.total(taxRate.percent()).toPlainString());
        if (!invoice.hasPlan()) {
            applyCredit(invoice);
        }
    }

    /**
     * Uses the client's credit on the invoice just sent, up to what it has due: the oldest payment's
     * credit first (lower id first on the same date), each part applied today and recorded in the audit.
     */
    private void applyCredit(Invoice invoice) {
        BigDecimal due = invoice.amountDue(paid(List.of(invoice)).get(invoice.getId()), taxRate.percent());
        LocalDate now = today.date();
        boolean used = false;
        for (Payment payment : payments.findForClient(invoice.getClientId())) {
            if (due.signum() <= 0) {
                break;
            }
            BigDecimal credit = payment.credit();
            if (credit.signum() <= 0) {
                continue;
            }
            BigDecimal part = credit.min(due);
            payment.apply(invoice.getId(), part, now);
            payments.saveAndFlush(payment);
            audit.record("PAYMENT_RECORDED id=" + payment.getId() + " invoice=" + invoice.getId() + " amount="
                    + part.setScale(invoice.scale(), RoundingMode.UNNECESSARY).toPlainString());
            due = due.subtract(part);
            used = true;
        }
        if (used) {
            recordWriteOff(invoice);
        }
    }

    /**
     * Records a deposit of the amount on the date from the client: the whole amount is held on the
     * client's account as credit, applied to no invoice, and recorded in the audit; empty if no such
     * client. The amount has already been checked; a date after today is refused.
     */
    @Transactional
    public Optional<Payment> recordDeposit(Long clientId, BigDecimal paidAmount, Currency paidIn, LocalDate date) {
        Optional<Client> client = clients.findById(clientId);
        if (client.isEmpty()) {
            return Optional.empty();
        }
        Currency currency = client.get().getCurrency();
        BigDecimal amount = converted(paidAmount, paidIn, currency);
        LocalDate now = today.date();
        if (date.isAfter(now)) {
            throw new InvoiceRejectedException("The payment date cannot be after today (" + now + ").");
        }
        Payment payment = payments.saveAndFlush(Payment.deposit(clientId, amount, date).paidIn(paidIn, paidAmount, currency));
        audit.record("DEPOSIT_RECORDED id=" + payment.getId() + " client=" + clientId + " amount="
                + amount.setScale(currency.scale(), RoundingMode.UNNECESSARY).toPlainString());
        return Optional.of(payment);
    }

    /**
     * Refunds the amount of the client's unused credit on the date: the oldest payment's credit first
     * (lower id first on the same date), then the next, each part recorded in the audit; empty if no
     * such client. The amount has already been checked; a date after today or before a payment it
     * would come from, or an amount more than the client's credit, is refused.
     */
    @Transactional
    public Optional<Long> refundCredit(Long clientId, BigDecimal amount, LocalDate date) {
        Optional<Client> client = clients.findById(clientId);
        if (client.isEmpty()) {
            return Optional.empty();
        }
        Currency currency = client.get().getCurrency();
        requireWhole(currency, amount, "The refund");
        LocalDate now = today.date();
        if (date.isAfter(now)) {
            throw new InvoiceRejectedException("The refund date cannot be after today (" + now + ").");
        }
        List<Payment> from = new ArrayList<>();
        List<BigDecimal> parts = new ArrayList<>();
        BigDecimal left = amount;
        BigDecimal credit = BigDecimal.ZERO;
        for (Payment payment : payments.findForClient(clientId)) {
            BigDecimal unused = payment.credit();
            if (unused.signum() <= 0) {
                continue;
            }
            credit = credit.add(unused);
            if (left.signum() <= 0) {
                continue;
            }
            BigDecimal part = unused.min(left);
            if (date.isBefore(payment.getDate())) {
                throw new InvoiceRejectedException("The refund cannot be dated before the payment of "
                        + payment.getDate() + " it would come from.");
            }
            from.add(payment);
            parts.add(part);
            left = left.subtract(part);
        }
        if (left.signum() > 0) {
            throw new InvoiceRejectedException("The refund cannot be more than the client's unused credit of "
                    + currency.format(credit) + ".");
        }
        for (int i = 0; i < from.size(); i++) {
            Payment payment = from.get(i);
            payment.refund(parts.get(i));
            payments.saveAndFlush(payment);
            audit.record("CREDIT_REFUNDED payment=" + payment.getId() + " amount="
                    + parts.get(i).setScale(currency.scale(), RoundingMode.UNNECESSARY).toPlainString());
        }
        return Optional.of(clientId);
    }

    /** The unused credit of each client that has any, by client id. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> credits() {
        Map<Long, BigDecimal> credits = new HashMap<>();
        for (Payment payment : payments.findNotReversed()) {
            BigDecimal credit = payment.credit();
            if (credit.signum() > 0) {
                credits.merge(payment.getClientId(), credit, BigDecimal::add);
            }
        }
        return credits;
    }

    /**
     * Voids a sent invoice with no payments, and records it in the audit; empty if no such invoice. A
     * draft, an invoice already void, or one with any payment is refused.
     */
    @Transactional
    public Optional<Invoice> voidInvoice(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException("This invoice is already void.");
            }
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("A draft cannot be voided; only a sent invoice can.");
            }
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("An issued credit note cannot be voided.");
            }
            if (invoice.hasCreditNotesIssued()) {
                throw new InvoiceRejectedException("An invoice with an issued credit note cannot be voided.");
            }
            if (!payments.findForInvoice(invoice.getId()).isEmpty()) {
                throw new InvoiceRejectedException("An invoice with payments cannot be voided.");
            }
            invoice.markVoid();
            invoices.saveAndFlush(invoice);
            audit.record("INVOICE_VOIDED id=" + invoice.getId() + " number=" + invoice.number());
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
     * checked; what it pays beyond what is due is held on the client's account as credit. A draft, an
     * invoice already paid, or a date after today
     * or before the invoice's issue date is refused. The reference (null when none was quoted) has
     * already been checked; one that belongs to a different invoice is refused.
     */
    @Transactional
    public Optional<Invoice> recordPayment(Long invoiceId, BigDecimal paidAmount, Currency paidIn, LocalDate date,
            String reference) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("Send the invoice before recording a payment.");
            }
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException("A void invoice takes no payments.");
            }
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note takes no payments.");
            }
            BigDecimal amount = converted(paidAmount, paidIn, invoice.currency());
            BigDecimal paidSoFar = paid(List.of(invoice)).get(invoice.getId());
            BigDecimal due = invoice.amountDue(paidSoFar, taxRate.percent());
            if (due.signum() <= 0) {
                throw new InvoiceRejectedException("This invoice has already been paid.");
            }
            LocalDate now = today.date();
            BigDecimal early = BigDecimal.ZERO;
            BigDecimal settle = date.isAfter(now) ? null : invoice.settleAmount(paidSoFar, taxRate.percent(), date);
            if (settle != null && amount.compareTo(settle) >= 0) {
                early = invoice.earlyDiscount(taxRate.percent());
                invoice.takeEarlyDiscount(early);
                due = settle;
            }
            BigDecimal applied = amount.min(due);
            if (invoice.hasPlan() && invoice.isTooSmallPart(applied, paidSoFar, taxRate.percent())) {
                throw new InvoiceRejectedException("A payment must be at least the "
                        + invoice.currency().format(invoice.earliestUnpaid(paidSoFar, taxRate.percent()))
                        + " still unpaid on the earliest unpaid instalment, unless it settles the plan.");
            }
            if (invoice.isTooSmallPart(applied, paidSoFar, taxRate.percent())) {
                throw new InvoiceRejectedException("A part payment must be at least "
                        + invoice.currency().format(invoice.minimumPart(taxRate.percent()))
                        + " (10% of the total), unless it settles the invoice.");
            }
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
            Payment payment = payments.saveAndFlush(Payment.against(invoice, amount, applied, date, reference)
                    .paidIn(paidIn, paidAmount, invoice.currency()));
            audit.record("PAYMENT_RECORDED id=" + payment.getId() + " invoice=" + invoice.getId() + " amount="
                    + applied.setScale(invoice.scale(), RoundingMode.UNNECESSARY).toPlainString());
            if (early.signum() > 0) {
                invoices.saveAndFlush(invoice);
                audit.record("EARLY_DISCOUNT id=" + invoice.getId() + " amount=" + early.toPlainString());
            }
            recordWriteOff(invoice);
            recordCreditHeld(payment);
        });
        return found;
    }

    /**
     * Records one payment of the amount on the date against the client's unpaid sent invoices, earliest
     * due date first (lower id first on the same due date) so overdue invoices are cleared first,
     * paying each off in full before the next.
     * What it pays beyond everything the client owes is held on the client's account as credit; the
     * rest is checked and recorded as a split payment over the invoices it reaches.
     * References (empty when none, each already checked) instead limit the payment to the client's
     * invoices with those references, applied in the same order; one that is not the reference of any
     * of the client's invoices is refused; what is paid beyond what those invoices owe together is held
     * as credit.
     * The payment keeps the reference of the first invoice it reaches.
     */
    @Transactional
    public Payment recordAutoPayment(Long clientId, BigDecimal paidAmount, Currency paidIn, LocalDate date,
            List<String> references) {
        BigDecimal amount = converted(paidAmount, paidIn, currencyOf(clientId));
        List<Invoice> ofClient = loadForClient(clientId);
        List<Invoice> candidates = ofClient;
        if (!references.isEmpty()) {
            Set<Long> named = new HashSet<>();
            for (String reference : references) {
                Invoice invoice = ofClient.stream()
                        .filter(i -> reference.equals(PaymentReference.of(i.getId())))
                        .findFirst()
                        .orElseThrow(() -> new InvoiceRejectedException(PaymentReference.grouped(reference)
                                + " is not the reference of any of this client's invoices."));
                String number = InvoiceNumber.of(invoice.getId());
                if (invoice.isDraft()) {
                    throw new InvoiceRejectedException("Send " + number + " before recording a payment against it.");
                }
                if (invoice.isVoid()) {
                    throw new InvoiceRejectedException(number + " is void and takes no payments.");
                }
                if (invoice.isCreditNote()) {
                    throw new InvoiceRejectedException(invoice.number() + " is a credit note and takes no payments.");
                }
                named.add(invoice.getId());
            }
            candidates = ofClient.stream().filter(i -> named.contains(i.getId())).toList();
        }
        List<Invoice> sent = candidates.stream()
                .filter(i -> !i.isDraft() && !i.isVoid() && !i.isCreditNote())
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
            throw new InvoiceRejectedException(references.isEmpty() ? "The client has no unpaid invoices to pay."
                    : "Those invoices have already been paid.");
        }
        String reference = references.isEmpty() ? null : PaymentReference.of(parts.get(0).getInvoiceId());
        return recordSpread(clientId, amount, paidAmount, paidIn, date, reference, parts, true);
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
    public Payment recordSplitPayment(Long clientId, BigDecimal paidAmount, Currency paidIn, LocalDate date,
            String reference, List<PaymentAllocation> parts) {
        BigDecimal amount = converted(paidAmount, paidIn, currencyOf(clientId));
        return recordSpread(clientId, amount, paidAmount, paidIn, date, reference, parts, false);
    }

    /**
     * Records the payment spread over the parts as {@link #recordSplitPayment} does, except that when
     * credit may be held the parts may add up to less than the payment: the rest is held on the
     * client's account as credit and recorded in the audit.
     */
    private Payment recordSpread(Long clientId, BigDecimal amount, BigDecimal paidAmount, Currency paidIn,
            LocalDate date, String reference, List<PaymentAllocation> parts, boolean holdCredit) {
        if (parts.isEmpty()) {
            throw new InvoiceRejectedException("Enter how much of the payment goes to each invoice.");
        }
        Currency currency = currencyOf(clientId);
        requireWhole(currency, amount, "The amount");
        for (PaymentAllocation part : parts) {
            requireWhole(currency, part.getAmount(), "Each invoice's part");
        }
        BigDecimal sum = parts.stream().map(PaymentAllocation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (holdCredit ? sum.compareTo(amount) > 0 : sum.compareTo(amount) != 0) {
            throw new InvoiceRejectedException("The amounts for the invoices add up to "
                    + currency.format(sum) + ", not the " + currency.format(amount) + " paid.");
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
            Invoice invoice = load(part.getInvoiceId())
                    .filter(i -> i.getClientId().equals(clientId))
                    .orElseThrow(() -> new InvoiceRejectedException("A part of the payment goes to an invoice of another client."));
            String number = InvoiceNumber.of(invoice.getId());
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("Send " + number + " before recording a payment against it.");
            }
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException(number + " is void and takes no payments.");
            }
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException(invoice.number() + " is a credit note and takes no payments.");
            }
            BigDecimal paidSoFar = paid(List.of(invoice)).get(invoice.getId());
            BigDecimal due = invoice.amountDue(paidSoFar, taxRate.percent());
            if (part.getAmount().compareTo(due) > 0) {
                throw new InvoiceRejectedException("The " + currency.format(part.getAmount()) + " for "
                        + number + " is more than the " + currency.format(due) + " it has due.");
            }
            if (invoice.hasPlan() && invoice.isTooSmallPart(part.getAmount(), paidSoFar, taxRate.percent())) {
                throw new InvoiceRejectedException("The " + currency.format(part.getAmount()) + " for "
                        + number + " is less than the " + currency.format(invoice.earliestUnpaid(paidSoFar, taxRate.percent()))
                        + " still unpaid on its earliest unpaid instalment, unless it settles the plan.");
            }
            if (invoice.isTooSmallPart(part.getAmount(), paidSoFar, taxRate.percent())) {
                throw new InvoiceRejectedException("The " + currency.format(part.getAmount()) + " for "
                        + number + " is less than " + currency.format(invoice.minimumPart(taxRate.percent()))
                        + " (10% of its total), unless it settles the invoice.");
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
        Payment payment = payments.saveAndFlush(Payment.split(clientId, amount, date, reference, parts)
                .paidIn(paidIn, paidAmount, currency));
        for (PaymentAllocation part : parts) {
            audit.record("PAYMENT_RECORDED id=" + payment.getId() + " invoice=" + part.getInvoiceId() + " amount="
                    + part.getAmount().setScale(currency.scale(), RoundingMode.UNNECESSARY).toPlainString());
        }
        spreadOver.forEach(this::recordWriteOff);
        recordCreditHeld(payment);
        return payment;
    }

    /**
     * The amount paid in the currency (null for the client's own) converted into the client's currency
     * through US dollars at the workspace rates; this is what is applied and checked. The amount must be
     * whole in the currency it was paid in, and must still be more than zero once converted.
     */
    private BigDecimal converted(BigDecimal paidAmount, Currency paidIn, Currency clientCurrency) {
        Currency from = paidIn == null ? clientCurrency : paidIn;
        requireWhole(from, paidAmount, "The amount");
        BigDecimal amount = exchangeRates.convert(paidAmount, from, clientCurrency);
        if (amount.signum() <= 0) {
            throw new InvoiceRejectedException("The amount is worth nothing once converted into "
                    + clientCurrency.name() + ".");
        }
        return amount;
    }

    /** The currency the client is billed in; US dollars when there is no such client. */
    private Currency currencyOf(Long clientId) {
        return clients.findById(clientId).map(Client::getCurrency).orElse(Currency.USD);
    }

    /**
     * Refuses an amount entered with more decimals than the currency has: whole yen for a yen client
     * (the cent is already checked for the rest).
     */
    private static void requireWhole(Currency currency, BigDecimal amount, String what) {
        if (amount != null && !currency.isWhole(amount)) {
            throw new InvoiceRejectedException(what + " must be in whole yen, with no decimals.");
        }
    }

    /** Records in the audit what a payment just recorded holds on the client's account as credit, if anything. */
    private void recordCreditHeld(Payment payment) {
        BigDecimal credit = payment.credit();
        if (credit.signum() > 0) {
            audit.record("CREDIT_HELD payment=" + payment.getId() + " amount="
                    + credit.setScale(currencyOf(payment.getClientId()).scale(), RoundingMode.UNNECESSARY).toPlainString());
        }
    }

    /** Records in the audit what a payment just recorded left written off on the invoice, if anything. */
    private void recordWriteOff(Invoice invoice) {
        withInterest(List.of(invoice));
        BigDecimal writtenOff = invoice.writtenOff(paid(List.of(invoice)).get(invoice.getId()), taxRate.percent());
        if (writtenOff.signum() > 0) {
            audit.record("INVOICE_WRITTEN_OFF id=" + invoice.getId() + " amount=" + writtenOff.toPlainString());
        }
    }

    /**
     * Reverses the payment (a bounced cheque), undoing it on every invoice it was applied to, and
     * records it in the audit; empty if no such payment. A payment already reversed, or one that has
     * had any of its credit refunded, or credit from a credit note, is refused.
     */
    @Transactional
    public Optional<Payment> reversePayment(Long paymentId) {
        Optional<Payment> found = payments.findById(paymentId);
        found.ifPresent(payment -> {
            if (payment.isReversed()) {
                throw new InvoiceRejectedException("This payment has already been reversed.");
            }
            if (payment.getCreditNote() != null) {
                throw new InvoiceRejectedException("This is credit from a credit note, so it cannot be reversed.");
            }
            if (payment.getRefunded().signum() > 0) {
                throw new InvoiceRejectedException("This payment has had credit refunded, so it cannot be reversed.");
            }
            payment.reverse();
            payments.saveAndFlush(payment);
            // An invoice the payment settled early is no longer paid off, so its early discount lapses.
            payment.getAllocations().forEach(a -> load(a.getInvoiceId()).ifPresent(invoice -> {
                if (invoice.getEarlyDiscountTaken().signum() > 0) {
                    invoice.takeEarlyDiscount(BigDecimal.ZERO);
                    invoices.saveAndFlush(invoice);
                }
            }));
            audit.record("PAYMENT_REVERSED id=" + payment.getId() + " amount="
                    + payment.getAmount().setScale(currencyOf(payment.getClientId()).scale(), RoundingMode.UNNECESSARY)
                            .toPlainString());
        });
        return found;
    }
}
