package net.officefloor.hq.app.invoices;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.hibernate.Hibernate;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.CardSurcharge;
import net.officefloor.hq.app.DuplicateWindow;
import net.officefloor.hq.app.ExchangeRates;
import net.officefloor.hq.app.FxFee;
import net.officefloor.hq.app.LateInterestRate;
import net.officefloor.hq.app.PriceList;
import net.officefloor.hq.app.Holidays;
import net.officefloor.hq.app.RegionTerms;
import net.officefloor.hq.app.TaxRate;
import net.officefloor.hq.app.Today;
import net.officefloor.hq.app.clients.Client;
import net.officefloor.hq.app.clients.ClientRate;
import net.officefloor.hq.app.clients.ClientRepository;
import net.officefloor.hq.app.clients.Currency;
import net.officefloor.hq.app.clients.RateTiers;
import net.officefloor.hq.app.clients.RateDiscount;
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
    private final PriceList priceList;
    private final CardSurcharge cardSurcharge;

    private final FxFee fxFee;

    private final RegionTerms regionTerms;
    private final Holidays holidays;

    private final DuplicateWindow duplicateWindow;

    public InvoiceService(InvoiceRepository invoices, PaymentRepository payments, ClientRepository clients,
            Today today, TaxRate taxRate, Audit audit, EntityManager entityManager, ExchangeRates exchangeRates,
            LateInterestRate lateInterestRate, CardSurcharge cardSurcharge, FxFee fxFee, PriceList priceList,
            RegionTerms regionTerms, DuplicateWindow duplicateWindow, Holidays holidays) {
        this.regionTerms = regionTerms;
        this.holidays = holidays;
        this.duplicateWindow = duplicateWindow;
        this.priceList = priceList;
        this.cardSurcharge = cardSurcharge;
        this.fxFee = fxFee;
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

    /**
     * Works out the late interest each of the invoices has been charged by today (see
     * {@link Invoice#accrueLateInterest}), and prices and taxes each draft as of today (see
     * {@link Invoice#pricingDate}): its lines priced from the rates follow those in force today.
     */
    private List<Invoice> withInterest(List<Invoice> list) {
        if (list.isEmpty()) {
            return list;
        }
        // Each invoice's risk band is judged on its client's other invoices, so load them all.
        Map<Long, List<Invoice>> byClient = new LinkedHashMap<>();
        list.stream().map(Invoice::getClientId).distinct()
                .forEach(c -> byClient.put(c, invoices.findByClientIdOrderByIdAsc(c)));
        Set<Long> given = list.stream().map(Invoice::getId).collect(java.util.stream.Collectors.toSet());
        List<Invoice> all = new ArrayList<>(list);
        byClient.values().stream().flatMap(List::stream).filter(i -> !given.contains(i.getId())).forEach(all::add);
        List<Payment> found = payments.findForInvoices(all.stream().map(Invoice::getId).toList());
        BigDecimal rate = taxRate.percent();
        BigDecimal annual = lateInterestRate.percent();
        LocalDate now = today.date();
        all.forEach(i -> {
            // Loaded with its lines only: read its milestones and reminders now, while the session is open.
            Hibernate.initialize(i.getMilestones());
            i.initializeReminders();
            i.priceAsOf(now);
            if (i.isDraft() && !i.isCreditNote()) {
                repriceWhereRated(i, now);
            }
            i.accrueLateInterest(found, rate, annual, now);
            i.notePromisePayments(found);
        });
        judgeWithholdingTogether(all, rate);
        Map<Long, LocalDate> paidOn = new HashMap<>();
        all.forEach(i -> {
            LocalDate on = i.paidOn(found, rate);
            if (on != null) {
                paidOn.put(i.getId(), on);
            }
        });
        list.forEach(i -> {
            List<Invoice> others = all.stream()
                    .filter(o -> o.getClientId().equals(i.getClientId()) && !o.getId().equals(i.getId())).toList();
            RiskBand band = riskOf(others, paidOn).band();
            i.withholdEarlyPay(band != RiskBand.LOW);
            i.halveApprovalLimit(band == RiskBand.MEDIUM);
            i.markHighRisk(band == RiskBand.HIGH);
            i.dropGrace(band == RiskBand.HIGH);
            if (band == RiskBand.HIGH) {
                i.accrueLateInterest(found, rate, annual, HIGH_RISK_INTEREST_PREMIUM, now);
            }
        });
        return list;
    }

    /**
     * Tells each sent invoice and debit note what it is judged together with for withholding (see
     * {@link Invoice#judgeWithholdingWith}): an invoice with its issued adjustment notes, credit notes taken off.
     */
    private static void judgeWithholdingTogether(List<Invoice> all, BigDecimal rate) {
        Map<Long, BigDecimal> together = new HashMap<>();
        Map<Long, BigDecimal> credited = new HashMap<>();
        for (Invoice i : all) {
            if (i.isDraft() || i.isVoid() || (i.isCreditNote() && !i.isAdjustment())) {
                continue;
            }
            Long group = i.isAdjustment() ? i.getAdjustmentOf() : i.getId();
            BigDecimal beforeTax = i.beforeTax(rate);
            together.merge(group, i.isCreditNote() ? beforeTax.negate() : beforeTax, BigDecimal::add);
            if (i.isCreditNote()) {
                credited.merge(group, beforeTax, BigDecimal::add);
            }
        }
        for (Invoice i : all) {
            if (i.isDraft() || i.isVoid() || i.isCreditNote()) {
                continue;
            }
            Long group = i.isAdjustment() ? i.getAdjustmentOf() : i.getId();
            BigDecimal others = together.getOrDefault(group, BigDecimal.ZERO).subtract(i.beforeTax(rate));
            i.judgeWithholdingWith(others, i.isAdjustment() ? BigDecimal.ZERO : credited.getOrDefault(group, BigDecimal.ZERO));
        }
    }

    /** The percentage points added to the late-interest rate of a HIGH payment risk client's invoices. */
    static final BigDecimal HIGH_RISK_INTEREST_PREMIUM = BigDecimal.valueOf(3);

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
            Invoice draft = Invoice.draft(clientId, client.isTaxExempt(), client.getTaxExemptUntil(), client.isPricesIncludeTax(), taxRate.forRegion(client.getRegion()),
                    taxRate.changesFor(client.getRegion()), taxRate.roundsDown(client.getRegion()),
                    outOfHomeRegion(client) ? client.getTaxNumber() : null, client.getCurrency(), issued, dueDateFor(clientId, issued));
            draft.setDiscountPct(client.getDiscountPct());
            return invoices.save(draft);
        });
    }

    /** Whether the client is in a region other than the workspace's home region (when it has one). */
    private boolean outOfHomeRegion(Client client) {
        String home = taxRate.homeRegion();
        return home != null && client.getRegion() != null && !client.getRegion().equals(home);
    }

    /**
     * A new draft for the invoice's client with copies of its lines and the same invoice discount
     * (not the client's standard ones), issued today and due as any new invoice is, and records it in the audit; empty if no such
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
     * Rebills the sent invoice, answering it; empty if no such invoice. A credit note dated today credits every line for
     * exactly what the invoice is worth (what it was sent for) and is issued as any credit note is, after every payment
     * made to the invoice has come off it; then a draft replacing it (see {@link Invoice#rebill}), issued today and due
     * as any new invoice is, gets those payments held for it as credit, used on it first when it is sent. The draft takes
     * over the invoice's open dispute, milestones and recurring plan (the invoice's dispute closes and it stops recurring),
     * and is approved already when its grand total is no more than the invoice was sent for plus the late interest it
     * carries. The late interest the invoice owes (once past any threshold) is carried: it is waived on the invoice today, so
     * stops there, and the draft bills it as a line of its own (see {@link InvoiceLine#lateInterest}), recorded as
     * INTEREST_CARRIED in the audit. Records INVOICE_REBILLED in the audit. A draft, a credit note, a void or written-off invoice, or one already credited is
     * refused.
     */
    @Transactional
    public Optional<Invoice> rebill(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be rebilled.");
            }
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a sent invoice can be rebilled.");
            }
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException("A void invoice cannot be rebilled.");
            }
            if (invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("A written-off invoice cannot be rebilled.");
            }
            if (invoice.hasCreditNotesIssued() || invoice.getRebilledOn() != null) {
                throw new InvoiceRejectedException("An invoice already credited cannot be rebilled.");
            }
            LocalDate on = today.date();
            BigDecimal rate = taxRate.percent();
            withInterest(List.of(invoice));
            BigDecimal interest = invoice.getLateInterest();
            List<Payment> paidBy = payments.findForInvoice(invoice.getId()).stream()
                    .filter(p -> !p.isReversed()).toList();
            paidBy.forEach(p -> {
                p.returnCredit(invoice.getId());
                payments.saveAndFlush(p);
            });
            Invoice note = invoices.saveAndFlush(invoice.creditNoteInFull(on, rate));
            entityManager.refresh(note);
            String refusal = creditNoteRefusal(note, false);
            if (refusal != null) {
                throw new InvoiceRejectedException(refusal);
            }
            issue(note);
            invoice.markRebilled(on);
            invoices.saveAndFlush(invoice);
            BigDecimal sentFor = invoice.total(rate);
            Invoice replacement = invoice.rebill(on, dueDateFor(invoice.getClientId(), on));
            if (interest.signum() > 0) {
                replacement.addLine(InvoiceLine.lateInterest(invoice.getId(), interest));
                invoice.waiveInterest(on);
            }
            Invoice next = invoices.saveAndFlush(replacement);
            invoice.handOver(on);
            invoices.saveAndFlush(invoice);
            // Read again how far through the bands a copied tiered line's units reach.
            entityManager.refresh(next);
            if (approval(next) == Invoice.Approval.NEEDS_APPROVAL) {
                if (next.total(rate).compareTo(sentFor.add(interest)) <= 0) {
                    next.approveInFull(next.approvalTotal(rate), on, rate);
                    invoices.saveAndFlush(next);
                }
            }
            paidBy.forEach(p -> {
                p.holdFor(next.getId());
                payments.saveAndFlush(p);
            });
            audit.record("INVOICE_REBILLED from=" + invoice.number() + " to=" + next.number());
            if (interest.signum() > 0) {
                audit.record("INTEREST_CARRIED from=" + invoice.number() + " to=" + next.number()
                        + " amount=" + interest.toPlainString());
            }
            entityManager.refresh(invoice);
            withInterest(List.of(invoice));
        });
        return found;
    }

    /**
     * Brings the sent invoice up to date, answering it; empty if no such invoice. Today's grand total and tax become what
     * it was sent with (see {@link Invoice#keepSentFigures}), so it owes what today's rules give, recorded as
     * INVOICE_UPDATED in the audit. Refused for a draft, a credit note, a void, written-off or rebilled invoice, when
     * today's grand total is no different from what it was sent for, when it would be less than what has been paid on it,
     * and when the increase would take the client's balance (less what is in dispute, plus its approved drafts) over its
     * credit limit.
     */
    @Transactional
    public Optional<Invoice> updateSentFigures(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be updated.");
            }
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a sent invoice can be updated.");
            }
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException("A void invoice cannot be updated.");
            }
            if (invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("A written-off invoice cannot be updated.");
            }
            if (invoice.getRebilledOn() != null) {
                throw new InvoiceRejectedException("A rebilled invoice cannot be updated.");
            }
            BigDecimal rate = taxRate.percent();
            BigDecimal from = invoice.total(rate);
            BigDecimal to = invoice.currentTotal(rate).setScale(from.scale(), RoundingMode.HALF_UP);
            BigDecimal increase = to.subtract(from);
            if (increase.signum() == 0) {
                throw new InvoiceRejectedException("The invoice's total today is what it was sent for; there is nothing to update.");
            }
            BigDecimal paidOn = paid(List.of(invoice)).get(invoice.getId());
            if (to.compareTo(paidOn) < 0) {
                throw new InvoiceRejectedException("Today's total (" + to.toPlainString() + ") is less than the "
                        + paidOn.toPlainString() + " already paid on the invoice, so it cannot be updated.");
            }
            if (increase.signum() > 0) {
                Client client = clients.findById(invoice.getClientId()).orElse(null);
                if (client != null && client.getCreditLimit() != null) {
                    BigDecimal after = creditBalance(client.getId()).add(committed(client.getId(), invoice.getId()))
                            .add(increase);
                    BigDecimal limit = creditLimitOf(client);
                    if (after.compareTo(limit) > 0) {
                        Currency currency = client.getCurrency();
                        throw new InvoiceRejectedException("Updating this invoice would take " + client.getName()
                                + "'s balance (less what is in dispute, plus its approved drafts) to " + currency.format(after)
                                + ", over its " + currency.format(limit) + " credit limit.");
                    }
                }
            }
            invoice.keepSentFigures(rate);
            invoices.saveAndFlush(invoice);
            audit.record("INVOICE_UPDATED id=" + invoice.getId() + " from=" + from.toPlainString()
                    + " to=" + to.toPlainString());
            entityManager.refresh(invoice);
            withInterest(List.of(invoice));
        });
        return found;
    }

    /**
     * Marks the invoice as recurring, or not; empty if no such invoice. A credit note is refused. The invoice
     * is otherwise unchanged, so a sent one stays sent.
     */
    @Transactional
    public Optional<Invoice> setRecurring(Long invoiceId, boolean recurring) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be recurring.");
            }
            invoice.setRecurring(recurring);
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Sets the last day the recurring invoice bills for (null for no end); empty if no such invoice. A credit note is refused.
     * When a sent recurring invoice has already billed days after the new end date, the plan ends early: a credit note dated
     * today credits each line with a service period for the part of its period after the end date (up to the end date it had
     * before, past which those days were credited already), charged by the day and taxed like the original (lines without a
     * period are not credited), and is issued as any credit note is (what is beyond what the invoice owes is held as credit).
     */
    @Transactional
    public Optional<Invoice> setRecurUntil(Long invoiceId, LocalDate recurUntil) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be recurring.");
            }
            LocalDate before = invoice.getRecurUntil();
            invoice.setRecurUntil(recurUntil);
            invoices.saveAndFlush(invoice);
            if (recurUntil != null && invoice.isRecurring() && !invoice.isDraft() && !invoice.isVoid()
                    && !invoice.isWrittenOff()) {
                creditAfterEnd(invoice, recurUntil, before);
            }
        });
        return found;
    }

    /** Issues the credit note for the days the invoice billed after its plan's new end date (see {@link #setRecurUntil}). */
    private void creditAfterEnd(Invoice invoice, LocalDate end, LocalDate before) {
        List<InvoiceLine> credits = invoice.creditsAfter(end, before);
        if (credits.isEmpty()) {
            return;
        }
        Invoice note = invoice.creditNote(today.date());
        credits.forEach(note::addLine);
        Invoice draft = invoices.saveAndFlush(note);
        entityManager.refresh(draft);
        // Its days are bounded by the end dates, so only what stops any credit note from being issued applies.
        String refusal = creditNoteRefusal(draft, false);
        if (refusal != null) {
            throw new InvoiceRejectedException(refusal);
        }
        issue(draft);
        entityManager.refresh(invoice);
        withInterest(List.of(invoice));
    }

    /**
     * Generates the next period's draft of a sent recurring invoice (see {@link Invoice#nextPeriod}), issued today and
     * due as any new invoice is, and records it in the audit; empty if no such invoice. A draft, an invoice that is
     * not recurring or has no line with a service period, one that has already generated its next period, one whose next
     * period would start after its end date, or one for a client on hold is refused. The draft is sent at once, as
     * {@link #send} would, when nothing stops it: no approval, deposit or more than the client's credit limit needed.
     */
    @Transactional
    public Optional<Invoice> generateNext(Long invoiceId) {
        return load(invoiceId).map(original -> {
            if (original.isDraft()) {
                throw new InvoiceRejectedException("Only a sent invoice can generate its next period.");
            }
            if (!original.isRecurring() || original.isCreditNote()) {
                throw new InvoiceRejectedException("The invoice is not recurring.");
            }
            if (original.isVoid()) {
                throw new InvoiceRejectedException("A void invoice cannot generate its next period.");
            }
            if (original.latestPeriodEnd() == null) {
                throw new InvoiceRejectedException("The invoice has no line with a service period to repeat.");
            }
            if (invoices.existsByRecurringFrom(original.getId())) {
                throw new InvoiceRejectedException("The invoice has already generated its next period.");
            }
            if (original.getRecurUntil() != null && original.nextPeriodStart().isAfter(original.getRecurUntil())) {
                throw new InvoiceRejectedException("The invoice's recurrence ended on " + original.getRecurUntil() + ".");
            }
            if (onHold(original.getClientId())) {
                throw new InvoiceRejectedException("The client is on hold: nothing is generated for them.");
            }
            LocalDate issued = today.date();
            Invoice next = invoices.saveAndFlush(original.nextPeriod(issued, dueDateFor(original.getClientId(), issued), upliftPct(original.getClientId())));
            // Read again how far through the bands a copied tiered line's units reach.
            entityManager.refresh(next);
            audit.record("INVOICE_GENERATED from=" + original.number() + " to=" + next.number());
            if (sendable(next) && withinLimitOrApproved(next)) {
                markSent(next);
            }
            return next;
        });
    }

    /**
     * Makes the next progress claim of a sent invoice (see {@link Invoice#nextClaim}), issued today and due as any new
     * invoice is, and records it in the audit; empty if no such invoice. A draft, a credit note, a void invoice, one with
     * no line billed by progress, or one already claimed from is refused.
     */
    @Transactional
    public Optional<Invoice> nextClaim(Long invoiceId) {
        return load(invoiceId).map(original -> {
            if (original.isDraft()) {
                throw new InvoiceRejectedException("Only a sent invoice can be claimed from.");
            }
            if (original.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be claimed from.");
            }
            if (original.isVoid()) {
                throw new InvoiceRejectedException("A void invoice cannot be claimed from.");
            }
            if (!original.hasProgressLines()) {
                throw new InvoiceRejectedException("The invoice has no line billed by percent complete.");
            }
            if (invoices.existsByClaimFrom(original.getId())) {
                throw new InvoiceRejectedException("The next claim has already been made from this invoice.");
            }
            LocalDate issued = today.date();
            Invoice next = invoices.saveAndFlush(original.nextClaim(issued, dueDateFor(original.getClientId(), issued)));
            entityManager.refresh(next);
            audit.record("PROGRESS_CLAIM from=" + original.number() + " to=" + next.number());
            return next;
        });
    }

    /**
     * A new, empty draft credit note against the sent invoice, issued today; empty if no such invoice.
     * A draft, a void invoice or a credit note is refused.
     */
    @Transactional
    public Optional<Invoice> creditNote(Long invoiceId) {
        return load(invoiceId).map(original -> {
            requireCreditable(original);
            return invoices.saveAndFlush(original.creditNote(today.date()));
        });
    }

    /**
     * Credits a line of the sent invoice: a copy of it, marked as crediting it, is added to the
     * invoice's draft credit note, or to a new one started today when there is none; answers that
     * draft, or empty if no such invoice or line. Refused as {@link #creditNote} is.
     */
    @Transactional
    public Optional<Invoice> creditLine(Long invoiceId, Long lineId) {
        return load(invoiceId).flatMap(original -> original.getLines().stream()
                .filter(l -> lineId.equals(l.getId())).findFirst().map(line -> {
                    requireCreditable(original);
                    Invoice note = loadForClient(original.getClientId()).stream()
                            .filter(i -> i.isDraft() && original.getId().equals(i.getCreditOf()))
                            .findFirst()
                            .orElseGet(() -> original.creditNote(today.date()));
                    note.addLine(line.creditCopy());
                    Invoice saved = invoices.saveAndFlush(note);
                    // Read again how far through the bands a credited tiered line's units reach.
                    entityManager.refresh(saved);
                    return saved;
                }));
    }

    /**
     * Refuses a period for a credit note's line crediting a line with a service period that is not
     * inside the period the credited line billed: it credits only days that line charged for.
     */
    private void requireWithinCreditedPeriod(Invoice invoice, Long lineId, LocalDate periodStart, LocalDate periodEnd) {
        if (!invoice.isCreditNote() || invoice.getCreditOf() == null) {
            return;
        }
        Long creditOfLine = invoice.getLines().stream().filter(l -> lineId.equals(l.getId()))
                .map(InvoiceLine::getCreditOfLine).findFirst().orElse(null);
        if (creditOfLine == null) {
            return;
        }
        load(invoice.getCreditOf()).flatMap(original -> original.getLines().stream()
                .filter(l -> creditOfLine.equals(l.getId()) && l.hasPeriod()).findFirst())
                .ifPresent(credited -> {
                    if (periodStart == null || periodEnd == null
                            || periodStart.isBefore(credited.getPeriodStart()) || periodEnd.isAfter(credited.getPeriodEnd())) {
                        throw new InvoiceRejectedException("The credit period must be inside the period the line billed, "
                                + credited.getPeriodStart() + " to " + credited.getPeriodEnd() + ".");
                    }
                });
    }

    /** Refuses to credit a credit note, a draft, a void invoice or a written-off one. */
    private void requireCreditable(Invoice original) {
        if (original.isCreditNote()) {
            throw new InvoiceRejectedException("A credit note cannot itself be credited.");
        }
        if (original.isDraft()) {
            throw new InvoiceRejectedException("Only a sent invoice can be credited.");
        }
        if (original.isVoid()) {
            throw new InvoiceRejectedException("A void invoice cannot be credited.");
        }
        if (original.isWrittenOff()) {
            throw new InvoiceRejectedException("A written-off invoice cannot be credited.");
        }
    }

    /**
     * An invoice for a client with payment terms is due that many days after it is issued; for a
     * client without, in a region with standard terms, the region's days after; otherwise as
     * {@link #dueDateFor(LocalDate)}.
     */
    private LocalDate dueDateFor(Long clientId, LocalDate issued) {
        return clients.findById(clientId)
                .flatMap(c -> Optional.ofNullable(c.getTermsDays()).or(() -> regionTerms.forRegion(c.getRegion())))
                .map(days -> holidays.after(issued, days))
                .orElseGet(() -> dueDateFor(issued));
    }

    /**
     * Shortens the due date of an invoice sent to a slow payer: a MEDIUM-risk client's is at most 21 days
     * after the issue date and a HIGH-risk client's at most 7; an earlier due date is kept.
     */
    private LocalDate cappedForRisk(Long clientId, LocalDate issued, LocalDate due) {
        LocalDate cap = switch (riskOf(clientId).band()) {
            case HIGH -> holidays.after(issued, 7);
            case MEDIUM -> holidays.after(issued, 21);
            default -> null;
        };
        return cap != null && due.isAfter(cap) ? cap : due;
    }

    /** A new invoice is due on the last day of the month after the month it is issued in. */
    static LocalDate dueDateFor(LocalDate issued) {
        LocalDate nextMonth = issued.plusMonths(1);
        return nextMonth.withDayOfMonth(nextMonth.lengthOfMonth());
    }

    /**
     * Adds a line to an invoice; empty if no such invoice. The line has already been checked. A sent
     * invoice is left as it was sent: the line is billed on a debit note instead (see {@link #issueDebitNote}).
     */
    @Transactional
    public Optional<Invoice> addLine(Long invoiceId, String description, BigDecimal qty, String unitType,
            BigDecimal unitPrice, boolean overtime, boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate,
            LocalDate periodStart, LocalDate periodEnd, BigDecimal percentComplete) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            boolean adjusting = adjustsByNotes(invoice);
            InvoiceLine line = newLine(invoice, description, qty, unitType, unitPrice, overtime, taxExempt, discountPct,
                    taxRate, periodStart, periodEnd, percentComplete);
            if (adjusting) {
                refuseDuplicateAdjustment(invoice, List.of(line), List.of());
                issueDebitNote(invoice, line);
                return;
            }
            invoice.addLine(line);
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Removes a line from an invoice; empty if no such invoice or line. Refused when what is left would
     * be less than a fixed discount. A sent invoice is left as it was sent: the line is taken off by a
     * credit note instead (see {@link #issueAdjustmentCredit}).
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
        if (adjustsByNotes(invoice)) {
            InvoiceLine credit = lineOf(invoice, lineId).creditCopy();
            refuseDuplicateAdjustment(invoice, List.of(), List.of(credit));
            issueAdjustmentCredit(invoice, credit);
            return found;
        }
        invoice.removeLine(lineId);
        invoices.saveAndFlush(invoice);
        return found;
    }

    /**
     * Changes a line of an invoice; empty if no such invoice or line. The line has already been
     * checked. Refused when the subtotal would be less than a fixed discount. A sent invoice is left as it
     * was sent and the change is made by adjustment notes instead: a change of quantity alone by one note for
     * the difference (a debit note for more, a credit note for less), any other change by a credit note taking
     * the old line off and a debit note billing the new one.
     */
    @Transactional
    public Optional<Invoice> updateLine(Long invoiceId, Long lineId, String description, BigDecimal qty,
            String unitType, BigDecimal unitPrice, boolean overtime, boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate,
            LocalDate periodStart, LocalDate periodEnd, BigDecimal percentComplete) {
        Optional<Invoice> found = load(invoiceId);
        if (found.isEmpty()) {
            return found;
        }
        Invoice invoice = found.get();
        if (!invoice.hasLine(lineId)) {
            return Optional.empty();
        }
        requireWithinCreditedPeriod(invoice, lineId, periodStart, periodEnd);
        if (adjustsByNotes(invoice)) {
            InvoiceLine old = lineOf(invoice, lineId);
            InvoiceLine changed = newLine(invoice, description, qty, unitType, unitPrice, overtime, taxExempt, discountPct,
                    taxRate, periodStart, periodEnd, percentComplete);
            if (!old.sameButQty(changed)) {
                refuseDuplicateAdjustment(invoice, List.of(changed), List.of(old.creditCopy()));
                issueAdjustmentCredit(invoice, old.creditCopy());
                issueDebitNote(invoice, changed);
            } else if (qty.compareTo(old.getQty()) > 0) {
                refuseDuplicateAdjustment(invoice, List.of(old.copy().withQty(qty.subtract(old.getQty()))), List.of());
                issueDebitNote(invoice, old.copy().withQty(qty.subtract(old.getQty())));
            } else if (qty.compareTo(old.getQty()) < 0) {
                refuseDuplicateAdjustment(invoice, List.of(), List.of(old.creditCopy().withQty(old.getQty().subtract(qty))));
                issueAdjustmentCredit(invoice, old.creditCopy().withQty(old.getQty().subtract(qty)));
            }
            return found;
        }
        LinePrice price = linePrice(invoice, unitType, unitPrice);
        invoice.updateLine(lineId, description, qty, unitType, price.unitPrice(), price.tiers(), price.increment(), price.minUnits(), overtime, taxExempt,
                discountPct, taxRate, periodStart, periodEnd, percentComplete);
        invoice.getLines().stream().filter(l -> lineId.equals(l.getId())).forEach(l -> l.setCardPriced(price.fromCard()));
        invoices.saveAndFlush(invoice);
        return found;
    }

    /** A new line for the invoice, priced as its lines are (see {@link #linePrice(Invoice, String, BigDecimal)}). */
    private InvoiceLine newLine(Invoice invoice, String description, BigDecimal qty, String unitType, BigDecimal unitPrice,
            boolean overtime, boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate, LocalDate periodStart,
            LocalDate periodEnd, BigDecimal percentComplete) {
        LinePrice price = linePrice(invoice, unitType, unitPrice);
        InvoiceLine line = new InvoiceLine(description, qty, unitType, price.unitPrice(), price.tiers(), price.increment(), price.minUnits(), overtime, taxExempt,
                discountPct, taxRate);
        line.setCardPriced(price.fromCard());
        line.setPeriod(periodStart, periodEnd);
        line.setPercentComplete(percentComplete);
        return line;
    }

    /**
     * Refuses a line change on the sent invoice, made by adjustment notes adding and taking off the lines given, that
     * would give it the same fingerprint (as adjusted) as another of its client's invoices sent within the duplicate
     * window (see {@link #duplicateCandidates}).
     */
    private void refuseDuplicateAdjustment(Invoice invoice, List<InvoiceLine> added, List<InvoiceLine> taken) {
        Integer days = duplicateWindow.days();
        if (days == null) {
            return;
        }
        List<Invoice> all = adjusted(invoices.findByClientIdOrderByIdAsc(invoice.getClientId()));
        invoice.adjustWith(notesOf(invoice, all));
        String fingerprint = invoice.adjustedFingerprint(added, taken);
        if (fingerprint == null) {
            return;
        }
        List<Invoice> same = duplicateCandidates(invoice, all, days).stream()
                .filter(i -> fingerprint.equals(i.fingerprint())).toList();
        if (!same.isEmpty()) {
            throw new InvoiceRejectedException("This change would make the invoice a duplicate of "
                    + same.stream().map(Invoice::number).collect(java.util.stream.Collectors.joining(", ")) + ".");
        }
    }

    /** The invoices, each with the adjustment notes issued against it set to take its fingerprint over (see {@link Invoice#adjustWith}). */
    private static List<Invoice> adjusted(List<Invoice> all) {
        all.forEach(i -> i.adjustWith(notesOf(i, all)));
        return all;
    }

    /** The adjustment notes issued (not draft, not void) against the invoice, among those given. */
    private static List<Invoice> notesOf(Invoice invoice, List<Invoice> all) {
        return all.stream().filter(n -> invoice.getId() != null && invoice.getId().equals(n.getAdjustmentOf())
                && !n.isDraft() && !n.isVoid()).toList();
    }

    /** Sets on the invoice the adjustment notes issued against it, which its fingerprint is taken over. */
    @Transactional(readOnly = true)
    public void adjustFingerprint(Invoice invoice) {
        invoice.adjustWith(notesOf(invoice, invoices.findByClientIdOrderByIdAsc(invoice.getClientId())));
    }

    private static InvoiceLine lineOf(Invoice invoice, Long lineId) {
        return invoice.getLines().stream().filter(l -> lineId.equals(l.getId())).findFirst().orElseThrow();
    }

    /**
     * Whether a change to the invoice's lines is made by adjustment notes, leaving the invoice as it was sent: it is
     * not a draft. A void or written-off invoice, a credit note, or one already credited or rebilled is refused.
     */
    private boolean adjustsByNotes(Invoice invoice) {
        if (invoice.isDraft()) {
            return false;
        }
        if (invoice.isVoid()) {
            throw new InvoiceRejectedException("A void invoice cannot be changed.");
        }
        if (invoice.isCreditNote()) {
            throw new InvoiceRejectedException("An issued credit note cannot be changed.");
        }
        if (invoice.isWrittenOff()) {
            throw new InvoiceRejectedException("A written-off invoice cannot be changed.");
        }
        if (invoice.hasCreditNotesIssued() || invoice.getRebilledOn() != null) {
            throw new InvoiceRejectedException("An invoice with an issued credit note cannot be changed.");
        }
        return true;
    }

    /**
     * Bills the line on a debit note adjusting the sent invoice: a new invoice for its client dated today, priced and
     * taxed as the invoice is, with no discount or charges, issued at once as any invoice sent today is (see
     * {@link #markSent}). A note that needs approval as any draft would (see {@link #approval}) is left a draft
     * note instead, to be approved and then sent.
     */
    private void issueDebitNote(Invoice invoice, InvoiceLine line) {
        LocalDate on = today.date();
        Invoice note = invoice.debitNote(on, dueDateFor(invoice.getClientId(), on));
        note.addLine(line);
        Invoice saved = invoices.saveAndFlush(note);
        // Read again the original's issue date it is priced and taxed as of.
        entityManager.refresh(saved);
        if (approval(saved) != Invoice.Approval.NEEDS_APPROVAL) {
            markSent(saved);
        }
        entityManager.refresh(invoice);
        withInterest(List.of(invoice));
    }

    /**
     * Takes the credited line off the sent invoice on a credit note adjusting it, dated today and taxed as the invoice
     * is, with no charges, issued as any credit note is (see {@link #issue}): it comes off what the invoice owes and any
     * rest is held as credit.
     */
    private void issueAdjustmentCredit(Invoice invoice, InvoiceLine credit) {
        Invoice note = invoice.adjustmentCreditNote(today.date());
        note.addLine(credit);
        Invoice draft = invoices.saveAndFlush(note);
        entityManager.refresh(draft);
        String refusal = creditNoteRefusal(draft, false);
        if (refusal != null) {
            throw new InvoiceRejectedException(refusal);
        }
        issue(draft);
        entityManager.refresh(invoice);
        withInterest(List.of(invoice));
    }

    /**
     * Gives an invoice a percentage discount, or a fixed amount off when one is given; the one given
     * clears the other, and the cap (null for none) limits everything taken off, line discounts included. Empty if no such invoice. The values have already been checked; a fixed amount
     * over the subtotal is refused; the reason is optional. A sent invoice's discount is fixed: it is refused.
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
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("The discount of a sent invoice cannot be changed.");
            }
            if (discountAmount != null) {
                invoice.setDiscountAmount(discountAmount);
            } else {
                invoice.setDiscountPct(discountPct);
            }
            invoice.setDiscountCap(discountCap);
            invoice.setDiscountReason(discountReason);
            invoices.saveAndFlush(invoice);
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
     * Sets the place of supply of a draft, the region where its work is done (blank for none, so the
     * client's region applies); empty if no such invoice. Any invoice that is not a draft is refused.
     */
    @Transactional
    public Optional<Invoice> setSupplyRegion(Long invoiceId, String supplyRegion) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a draft's place of supply can be set.");
            }
            invoice.setSupplyRegion(supplyRegion);
            invoices.saveAndFlush(invoice);
            // Read again the rate, rounding and tax number of the region of supply.
            entityManager.refresh(invoice);
        });
        return found;
    }

    /** Sets the client's purchase order number on an invoice (blank for none); empty if no such invoice. */
    @Transactional
    public Optional<Invoice> setPoNumber(Long invoiceId, String poNumber) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            invoice.setPoNumber(poNumber);
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Sets the rush surcharge of a draft, a percentage of its amount after its discounts; empty if no
     * such invoice. The percentage has already been checked; any invoice that is not a draft, or a
     * credit note, is refused.
     */
    @Transactional
    public Optional<Invoice> setSurcharge(Long invoiceId, BigDecimal surchargePct) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note carries the surcharge of the invoice it credits.");
            }
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("A surcharge can only be set on a draft.");
            }
            invoice.setSurchargePct(surchargePct);
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Adds a milestone of the percentage to a draft; empty if no such invoice. The percentage has already
     * been checked to be over 0; one that would take the milestones over 100% is refused, as is any invoice
     * that is not a draft, or a credit note.
     */
    @Transactional
    public Optional<Invoice> addMilestone(Long invoiceId, String description, BigDecimal pct) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be billed by milestones.");
            }
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a draft can have milestones added.");
            }
            BigDecimal after = invoice.milestonesPct().add(pct);
            if (after.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new InvoiceRejectedException("The milestones would add up to "
                        + after.stripTrailingZeros().toPlainString() + "%, over 100%.");
            }
            invoice.addMilestone(new Milestone(description, pct));
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Marks a milestone of a sent invoice reached today, records it in the audit with its amount, and
     * then uses the deposits held for the invoice and the client's other credit on what is now owed, as sending does (see {@link #applyCredit}); empty
     * if no such invoice. A milestone already reached, or of an invoice that is not sent, void, written off
     * or a credit note, is refused.
     */
    @Transactional
    public Optional<Invoice> reachMilestone(Long invoiceId, Long milestoneId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            Milestone milestone = invoice.milestone(milestoneId);
            if (milestone == null) {
                throw new InvoiceRejectedException("The invoice has no such milestone.");
            }
            if (invoice.getStatus() != InvoiceStatus.SENT || invoice.isCreditNote() || invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("Only a milestone of a sent invoice can be reached.");
            }
            if (milestone.isReached()) {
                throw new InvoiceRejectedException("This milestone has already been reached.");
            }
            milestone.reach(today.date());
            invoices.saveAndFlush(invoice);
            BigDecimal amount = invoice.milestoneAmounts(taxRate.percent())
                    .get(invoice.getMilestones().indexOf(milestone));
            audit.record("MILESTONE_REACHED id=" + invoice.getId() + " milestone=" + milestone.getId()
                    + " amount=" + amount.toPlainString());
            withInterest(List.of(invoice));
            applyCredit(invoice);
        });
        return found;
    }

    /**
     * Puts a reached milestone of a sent invoice in dispute today, and records it in the audit with what the
     * milestone owes, its late interest included; empty if no such invoice. From then on it earns no interest,
     * is not overdue and takes no payments, which go to the other reached milestones instead, until the dispute
     * is resolved. A milestone not reached, paid off or already in dispute is refused, as is one of an invoice
     * that is not sent, written off, or itself in dispute.
     */
    @Transactional
    public Optional<Invoice> disputeMilestone(Long invoiceId, Long milestoneId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            Milestone milestone = invoice.milestone(milestoneId);
            if (milestone == null) {
                throw new InvoiceRejectedException("The invoice has no such milestone.");
            }
            if (invoice.getStatus() != InvoiceStatus.SENT || invoice.isCreditNote() || invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("Only a milestone of a sent invoice can be disputed.");
            }
            if (!milestone.isReached()) {
                throw new InvoiceRejectedException("Only a reached milestone can be disputed.");
            }
            if (milestone.isDisputed()) {
                throw new InvoiceRejectedException("This milestone is already in dispute.");
            }
            if (invoice.isDisputed() && !invoice.hasDisputedMilestones()) {
                throw new InvoiceRejectedException("This invoice is already in dispute.");
            }
            BigDecimal rate = taxRate.percent();
            if (owedOn(invoice, milestone, rate).signum() <= 0) {
                throw new InvoiceRejectedException("This milestone has been paid off.");
            }
            invoice.disputeMilestone(milestone, today.date());
            invoices.saveAndFlush(invoice);
            withInterest(List.of(invoice));
            audit.record("INVOICE_DISPUTED id=" + invoice.getId() + " amount=" + owedOn(invoice, milestone, rate).toPlainString());
        });
        return found;
    }

    /** What is still owed on the milestone of the invoice, its late interest included. */
    private BigDecimal owedOn(Invoice invoice, Milestone milestone, BigDecimal rate) {
        return invoice.milestoneProgress(paid(List.of(invoice)).get(invoice.getId()), rate, today.date()).stream()
                .filter(p -> p.milestone() == milestone).map(Milestone.Progress::owed).findFirst().orElseThrow();
    }

    /** Why the draft's milestones keep it from being sent, or null when they do not: they must add up to exactly 100%. */
    private static String milestoneRefusal(Invoice invoice) {
        if (invoice.getMilestones().isEmpty() || invoice.milestonesPct().compareTo(BigDecimal.valueOf(100)) == 0) {
            return null;
        }
        return "The milestones add up to " + invoice.milestonesPct().stripTrailingZeros().toPlainString()
                + "%; they must add up to exactly 100% before the invoice is sent.";
    }

    /**
     * Waives the late interest still unpaid on a sent invoice today, writing it off, and
     * records what was waived in the audit; empty if no such invoice. Interest starts again from today on
     * what is still owed. Refused when there is nothing to waive.
     */
    @Transactional
    public Optional<Invoice> waiveInterest(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("The late charges were written off with the invoice.");
            }
            BigDecimal before = invoice.getLateWaived();
            invoice.waiveInterest(today.date());
            withInterest(List.of(invoice));
            BigDecimal waived = invoice.getLateWaived().subtract(before);
            if (waived.signum() <= 0) {
                throw new InvoiceRejectedException("There is no unpaid late interest to waive.");
            }
            invoices.saveAndFlush(invoice);
            audit.record("INTEREST_WAIVED id=" + invoice.getId() + " amount=" + waived.toPlainString());
        });
        return found;
    }

    /**
     * Records a promise made today to pay the amount on an overdue invoice by the date, and records it in the
     * audit; empty if no such invoice. The amount must be at least a tenth of what the invoice owes and no more
     * than it, the date after today and at most {@link #PROMISE_DAYS} days ahead. An invoice that is not overdue,
     * or whose last promise was broken, is refused.
     */
    @Transactional
    public Optional<Invoice> promisePayment(Long invoiceId, BigDecimal amount, LocalDate by) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            LocalDate now = today.date();
            BigDecimal rate = taxRate.percent();
            BigDecimal paid = paid(List.of(invoice)).get(invoice.getId());
            if (invoice.isPromiseBroken(now)) {
                throw new InvoiceRejectedException("The last promise to pay on this invoice was broken; it takes no new one.");
            }
            if (invoice.statusIgnoringPromise(paid, null, rate, now) != InvoiceStatus.OVERDUE) {
                throw new InvoiceRejectedException("Only an overdue invoice can take a promise to pay.");
            }
            BigDecimal owed = invoice.amountDue(paid, rate);
            BigDecimal promised = amount.setScale(2, RoundingMode.HALF_UP);
            if (promised.compareTo(owed.divide(BigDecimal.TEN, 2, RoundingMode.UP)) < 0) {
                throw new InvoiceRejectedException("The promise must be at least 10% of what the invoice owes.");
            }
            if (promised.compareTo(owed) > 0) {
                throw new InvoiceRejectedException("The promise can be no more than what the invoice owes.");
            }
            if (!by.isAfter(now) || by.isAfter(now.plusDays(PROMISE_DAYS))) {
                throw new InvoiceRejectedException("The promise must be kept by a date after today and at most "
                        + PROMISE_DAYS + " days ahead.");
            }
            invoice.promise(promised, by, now);
            invoices.saveAndFlush(invoice);
            audit.record("PAYMENT_PROMISED id=" + invoice.getId() + " amount=" + promised.toPlainString()
                    + " date=" + by);
        });
        return found;
    }

    /**
     * Sends today the reminder for the dunning level an invoice has reached, charging the level's reminder fee (see
     * {@link Invoice#reminderFee}), and records both in the audit; empty if
     * no such invoice. Refused when the invoice is at NONE or the reminder for its level has already gone out.
     */
    @Transactional
    public Optional<Invoice> remind(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            LocalDate now = today.date();
            List<Invoice> one = List.of(invoice);
            InvoiceStatus status = invoice.statusWhenPaid(paid(one).get(invoice.getId()),
                    paidOn(one).get(invoice.getId()), taxRate.percent(), now);
            DunningLevel level = invoice.dunningLevel(status, now);
            if (level == DunningLevel.NONE) {
                throw new InvoiceRejectedException("This invoice is not far enough overdue to be chased.");
            }
            if (invoice.getReminders().size() >= level.reminders()) {
                throw new InvoiceRejectedException("The " + level + " reminder has already gone out.");
            }
            invoice.remind(now);
            invoices.saveAndFlush(invoice);
            audit.record("REMINDER_SENT id=" + invoice.getId() + " level=" + level);
            BigDecimal fee = invoice.reminderFee(level, now);
            if (fee.signum() > 0) {
                audit.record("REMINDER_FEE id=" + invoice.getId() + " amount=" + fee.toPlainString());
            }
        });
        return found;
    }

    /** The most days ahead a promise to pay can be kept by. */
    static final int PROMISE_DAYS = 30;

    /**
     * Writes off a sent invoice that still has something owed as a bad debt today: everything still
     * owed on it, late charges included, and records what was written off in the audit;
     * empty if no such invoice. It then owes nothing, earns no more interest and takes no payments. A
     * draft, a void invoice, a credit note, one already written off or one with nothing owed is refused.
     */
    @Transactional
    public Optional<Invoice> writeOff(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("A draft cannot be written off; only a sent invoice can.");
            }
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException("A void invoice cannot be written off.");
            }
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be written off.");
            }
            if (invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("This invoice has already been written off.");
            }
            BigDecimal rate = taxRate.percent();
            BigDecimal paid = paid(List.of(invoice)).get(invoice.getId());
            if (invoice.amountDue(paid, rate).signum() <= 0) {
                throw new InvoiceRejectedException("There is nothing owed on this invoice to write off.");
            }
            invoice.writeOff(today.date());
            withInterest(List.of(invoice));
            BigDecimal amount = invoice.writtenOff(paid, rate);
            invoices.saveAndFlush(invoice);
            audit.record("INVOICE_WRITTEN_OFF id=" + invoice.getId() + " amount=" + amount.toPlainString());
        });
        return found;
    }

    /**
     * Puts a sent invoice that still has something due in dispute today, and records it with the amount
     * due in the audit; empty if no such invoice. While in dispute it earns no interest (what was charged
     * before stays) and is not overdue; a payment reaching it resolves the dispute. A draft, a void or written-off invoice, a credit
     * note, one already in dispute or one with nothing due is refused.
     */
    @Transactional
    public Optional<Invoice> dispute(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("A draft cannot be disputed; only a sent invoice can.");
            }
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException("A void invoice cannot be disputed.");
            }
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be disputed.");
            }
            if (invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("A written-off invoice cannot be disputed.");
            }
            if (invoice.isDisputed()) {
                throw new InvoiceRejectedException("This invoice is already in dispute.");
            }
            BigDecimal paid = paid(List.of(invoice)).get(invoice.getId());
            BigDecimal due = invoice.amountDue(paid, taxRate.percent());
            if (due.signum() <= 0) {
                throw new InvoiceRejectedException("There is nothing due on this invoice to dispute.");
            }
            invoice.dispute(today.date());
            invoices.saveAndFlush(invoice);
            withInterest(List.of(invoice));
            audit.record("INVOICE_DISPUTED id=" + invoice.getId() + " amount=" + due.toPlainString());
        });
        return found;
    }

    /**
     * Puts one line of a sent invoice in dispute, and records it in the audit with the amount then in dispute;
     * empty if no such invoice. The line joins an open dispute over other lines, or starts one today. From the
     * dispute date the disputed lines (after their share of the discount, with their tax) earn no interest and
     * are not overdue, while the rest of the invoice is owed as usual. Refused as {@link #dispute} is, and for a
     * line not on the invoice, one already disputed, or while the whole invoice is in dispute.
     */
    @Transactional
    public Optional<Invoice> disputeLine(Long invoiceId, Long lineId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isDraft()) {
                throw new InvoiceRejectedException("A draft cannot be disputed; only a sent invoice can.");
            }
            if (invoice.isVoid()) {
                throw new InvoiceRejectedException("A void invoice cannot be disputed.");
            }
            if (invoice.isCreditNote()) {
                throw new InvoiceRejectedException("A credit note cannot be disputed.");
            }
            if (invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("A written-off invoice cannot be disputed.");
            }
            if (invoice.isWhollyDisputed()) {
                throw new InvoiceRejectedException("The whole invoice is already in dispute.");
            }
            if (invoice.hasDisputedMilestones()) {
                throw new InvoiceRejectedException("A milestone of this invoice is already in dispute.");
            }
            InvoiceLine line = invoice.getLines().stream().filter(l -> lineId.equals(l.getId())).findFirst()
                    .orElseThrow(() -> new InvoiceRejectedException("There is no such line on this invoice."));
            if (invoice.isDisputed() && line.isDisputed()) {
                throw new InvoiceRejectedException("This line is already in dispute.");
            }
            BigDecimal rate = taxRate.percent();
            if (invoice.amountDue(paid(List.of(invoice)).get(invoice.getId()), rate).signum() <= 0) {
                throw new InvoiceRejectedException("There is nothing due on this invoice to dispute.");
            }
            invoice.disputeLine(line, today.date());
            invoices.saveAndFlush(invoice);
            withInterest(List.of(invoice));
            BigDecimal amount = invoice.disputedAmount(paid(List.of(invoice)).get(invoice.getId()), rate);
            audit.record("INVOICE_DISPUTED id=" + invoice.getId() + " amount=" + amount.toPlainString());
        });
        return found;
    }

    /**
     * Resolves the dispute on an invoice today, and records it in the audit; empty if no such invoice.
     * The dispute then holds no interest back: the invoice owes the interest it would have earned had it never
     * been disputed, the days in dispute included. Refused when it is not in dispute.
     */
    @Transactional
    public Optional<Invoice> resolveDispute(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.isDisputed()) {
                throw new InvoiceRejectedException("This invoice is not in dispute.");
            }
            invoice.resolveDispute(today.date());
            invoices.saveAndFlush(invoice);
            withInterest(List.of(invoice));
            audit.record("DISPUTE_RESOLVED id=" + invoice.getId());
        });
        return found;
    }

    /**
     * Resolves the dispute on an invoice today by crediting what was in dispute; empty if no such invoice.
     * A credit note dated today credits each disputed line in full (every line when the whole invoice is in
     * dispute), taxed like the original, and is issued as any credit note is (what is beyond what the invoice
     * owes is held as credit); then the dispute is resolved and recorded in the audit. The interest the
     * dispute held back on the disputed amount is never charged. Refused when it is not in dispute, or as
     * crediting the invoice is.
     */
    @Transactional
    public Optional<Invoice> resolveDisputeWithCredit(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.isDisputed()) {
                throw new InvoiceRejectedException("This invoice is not in dispute.");
            }
            if (invoice.hasDisputedMilestones()) {
                throw new InvoiceRejectedException("A disputed milestone cannot be resolved with a credit note.");
            }
            requireCreditable(invoice);
            LocalDate on = today.date();
            Invoice note = invoice.creditNote(on);
            invoice.disputedLines().forEach(line -> note.addLine(line.creditCopy()));
            String refusal = overCreditRefusal(note, invoice);
            if (refusal != null) {
                throw new InvoiceRejectedException(refusal);
            }
            invoice.resolveDisputeWithCredit(on);
            invoices.saveAndFlush(invoice);
            Invoice draft = invoices.saveAndFlush(note);
            entityManager.refresh(draft);
            refusal = creditNoteRefusal(draft);
            if (refusal != null) {
                throw new InvoiceRejectedException(refusal);
            }
            issue(draft);
            // Read again what it has been credited.
            entityManager.refresh(invoice);
            withInterest(List.of(invoice));
            audit.record("DISPUTE_RESOLVED id=" + invoice.getId());
        });
        return found;
    }

    /**
     * Puts a single price for the unit type on the client's rate card: replaces the price of its entry in force
     * today (its date, increment, minimum units and tax rate are kept), or adds an entry that has always applied
     * when none is; empty if no such client. The price has already been checked. The lines of the client's drafts
     * priced from the rates in that unit type are repriced straight away; sent invoices keep the prices they were
     * sent with.
     */
    @Transactional
    public Optional<Client> setRate(Long clientId, String unitType, BigDecimal unitPrice) {
        Optional<Client> found = clients.findById(clientId);
        found.ifPresent(client -> {
            String type = unitType.trim();
            ClientRate old = client.rateFor(type, today.date());
            requireWhole(client.getCurrency(), unitPrice, "The price");
            if (old != null) {
                client.getRates().remove(old);
            }
            client.getRates().add(old == null ? new ClientRate(type, null, unitPrice, null, null)
                    : new ClientRate(old.getUnitType(), old.getEffectiveFrom(), unitPrice, old.getIncrement(), old.getMinUnits(),
                            old.getTaxRate()));
            clients.saveAndFlush(client);
            for (Invoice invoice : invoices.findByClientIdOrderByIdAsc(clientId)) {
                if (!invoice.isDraft()) {
                    continue;
                }
                if (repriceFromRates(invoice, type, ratesDate(invoice))) {
                    invoices.saveAndFlush(invoice);
                }
            }
        });
        return found;
    }

    /**
     * Reprices the draft's lines priced from the rates at those in force on the date, leaving as it is any line
     * whose unit type has no rate in force then.
     */
    private void repriceWhereRated(Invoice invoice, LocalDate on) {
        for (InvoiceLine line : invoice.getLines()) {
            if (line.isCardPriced() && line.getUnitType() != null) {
                try {
                    LinePrice price = linePrice(invoice, line.getUnitType(), null, on);
                    line.reprice(price.unitPrice(), price.tiers(), price.increment(), price.minUnits());
                } catch (InvoiceRejectedException noRate) {
                    // no rate in force on the date: the line keeps the price it has
                }
            }
        }
    }

    /**
     * Reprices the draft's lines priced from the rates (all of them, or those of the given unit type) at the
     * rates in force on the date; whether any was.
     */
    private boolean repriceFromRates(Invoice invoice, String unitType, LocalDate on) {
        boolean changed = false;
        for (InvoiceLine line : invoice.getLines()) {
            if (line.isCardPriced() && line.getUnitType() != null
                    && (unitType == null || line.getUnitType().trim().equalsIgnoreCase(unitType))) {
                LinePrice price = linePrice(invoice, line.getUnitType(), null, on);
                line.reprice(price.unitPrice(), price.tiers(), price.increment(), price.minUnits());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Sends a draft invoice, issuing it today and making it due on the client's terms (or keeping a due
     * date set by hand), and records it in the audit; empty if no such invoice. An invoice already
     * sent, with no lines, whose due date set by hand is before today, or that needs approval, is refused.
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
            if (onHold(invoice.getClientId())) {
                throw new InvoiceRejectedException("The client is on hold: lift the hold before sending their invoices.");
            }
            if (invoice.isCreditNote()) {
                String refusal = creditNoteRefusal(invoice);
                if (refusal != null) {
                    throw new InvoiceRejectedException(refusal);
                }
                issue(invoice);
                return;
            }
            String milestones = milestoneRefusal(invoice);
            if (milestones != null) {
                throw new InvoiceRejectedException(milestones);
            }
            if (invoice.dueDatePassedBy(today.date())) {
                throw new InvoiceRejectedException("The due date (" + invoice.getDueDate()
                        + ") is already past; set a later due date before sending the invoice.");
            }
            if (approval(invoice) == Invoice.Approval.NEEDS_APPROVAL) {
                throw new InvoiceRejectedException(
                        invoice.discountIsLarge()
                                ? "The discount is more than 20% of the subtotal; approve the invoice before sending it."
                                : !possibleDuplicateOf(invoice).isEmpty()
                                        ? "This invoice may be a duplicate of " + possibleDuplicateOf(invoice).stream()
                                                .map(Invoice::number).collect(java.util.stream.Collectors.joining(", "))
                                                + "; approve the invoice before sending it."
                                : riskOf(invoice.getClientId()).band() == RiskBand.HIGH
                                        ? "The client pays slowly (HIGH payment risk); approve the invoice before sending it."
                                        : wouldGoOverLimit(invoice)
                                                ? creditLimitRefusal(invoice) + " Approve the invoice before sending it."
                                                : "The grand total is over the approval limit; approve the invoice before sending it.");
            }
            String noDeposit = depositRefusal(invoice);
            if (noDeposit != null) {
                throw new InvoiceRejectedException(noDeposit);
            }
            // An approved invoice may take the client over its credit limit (the client then goes on hold).
            String overLimit = approval(invoice) == Invoice.Approval.APPROVED ? null : creditLimitRefusal(invoice);
            if (overLimit != null) {
                throw new InvoiceRejectedException(overLimit);
            }
            markSent(invoice);
        });
        return found;
    }

    /**
     * Approves a draft whose grand total is over the workspace's approval limit, whose discount takes
     * off more than a fifth of the subtotal, or that would take its client over its credit limit, keeping that total and the day,
     * and records it in the audit; empty if no such invoice. A draft more than one and a half times the limit, or of a HIGH payment risk client, needs a
     * second approval of the same total. An invoice that needs no approval, or is already approved at its total, is refused.
     */
    @Transactional
    public Optional<Invoice> approve(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a draft invoice can be approved.");
            }
            BigDecimal rate = taxRate.percent();
            LocalDate on = today.date();
            Invoice.Approval approval = approval(invoice);
            if (approval != Invoice.Approval.NEEDS_APPROVAL) {
                throw new InvoiceRejectedException(approval == Invoice.Approval.APPROVED
                        ? "This invoice is already approved."
                        : "This invoice is within the approval limit, its discount is not over 20%, its client is not a HIGH payment risk, it would not take the client over its credit limit and it is not a possible duplicate, so it needs no approval.");
            }
            BigDecimal total = invoice.approvalTotal(rate);
            invoice.approve(total, on);
            invoices.saveAndFlush(invoice);
            audit.record("INVOICE_APPROVED id=" + invoice.getId() + " total=" + total.toPlainString());
        });
        return found;
    }

    /**
     * Sends each of the client's drafts that has at least one line, lowest id first, as {@link #send}
     * would; drafts with no lines, whose due date set by
     * hand is already past, or that need approval (including those that would take the client over its credit
     * limit, judged on its balance after the drafts sent before them, and are not approved), stay drafts.
     */
    @Transactional
    public void sendAllDrafts(Long clientId) {
        if (onHold(clientId)) {
            return;
        }
        loadForClient(clientId).stream()
                .filter(this::sendable)
                .forEach(invoice -> {
                    if (!invoice.isCreditNote()) {
                        // Read again the client's sent invoices: one sent just before counts towards its month's volume.
                        entityManager.refresh(invoice);
                        if (withinLimitOrApproved(invoice)) {
                            markSent(invoice);
                        }
                    } else if (creditNoteRefusal(invoice) == null) {
                        issue(invoice);
                    }
                });
    }

    /**
     * Whether the draft may be sent as far as its client's credit limit goes: it is approved, or sending it
     * would not take the client over the limit. A client already over its limit (on hold) sends nothing.
     */
    private boolean withinLimitOrApproved(Invoice invoice) {
        if (overLimit(invoice.getClientId())) {
            return false;
        }
        return approval(invoice) == Invoice.Approval.APPROVED || creditLimitRefusal(invoice) == null;
    }

    /**
     * Whether nothing but the client's credit limit stops the draft being sent as {@link #send} would: it has
     * lines, its due date set by hand is not past, it needs no approval, its milestones allow it and any
     * deposit it needs is held. The client's hold is checked by the caller.
     */
    private boolean sendable(Invoice invoice) {
        return invoice.isDraft() && !invoice.getLines().isEmpty()
                && !invoice.dueDatePassedBy(today.date())
                && approval(invoice) != Invoice.Approval.NEEDS_APPROVAL
                && milestoneRefusal(invoice) == null
                && (invoice.isCreditNote() || depositRefusal(invoice) == null);
    }

    /**
     * What the client owes: what is still due on its sent invoices (late interest included, withholding
     * left out), less its unused credit. Void invoices and credit notes
     * do not count (a credit note comes off its original).
     */
    @Transactional(readOnly = true)
    public BigDecimal balance(Long clientId) {
        BigDecimal rate = taxRate.percent();
        List<Invoice> sent = loadForClient(clientId).stream()
                .filter(i -> !i.isDraft() && !i.isVoid() && !i.isCreditNote()).toList();
        Map<Long, BigDecimal> paid = paid(sent);
        BigDecimal owed = BigDecimal.ZERO.setScale(2);
        for (Invoice i : sent) {
            owed = owed.add(i.amountDue(paid.get(i.getId()), rate));
        }
        return owed.subtract(credits().getOrDefault(clientId, BigDecimal.ZERO)).setScale(2);
    }

    /**
     * What counts against the client's credit limit: its balance (see {@link #balance}) less what is in
     * dispute on its sent invoices (whole invoices, lines or milestones; see {@link Invoice#disputedAmount}).
     */
    @Transactional(readOnly = true)
    public BigDecimal creditBalance(Long clientId) {
        BigDecimal rate = taxRate.percent();
        List<Invoice> sent = loadForClient(clientId).stream()
                .filter(i -> !i.isDraft() && !i.isVoid() && !i.isCreditNote()).toList();
        Map<Long, BigDecimal> paid = paid(sent);
        BigDecimal disputed = BigDecimal.ZERO.setScale(2);
        for (Invoice i : sent) {
            disputed = disputed.add(i.disputedAmount(paid.get(i.getId()), rate));
        }
        return balance(clientId).subtract(disputed).setScale(2);
    }

    /**
     * What the client's approved drafts will add to its balance when sent (each one's grand total less the
     * withholding), leaving out the draft with the given id (null for none): an approved draft is as good as
     * sent, so it counts against the credit limit. A draft is approved while the approvals it was given still
     * stand for its grand total (see {@link Invoice#approval}). Credit notes do not count.
     */
    @Transactional(readOnly = true)
    public BigDecimal committed(Long clientId, Long excludeId) {
        BigDecimal rate = taxRate.percent();
        LocalDate on = today.date();
        return loadForClient(clientId).stream()
                .filter(i -> i.isDraft() && !i.isCreditNote() && !i.getId().equals(excludeId)
                        && i.approval(rate, on, false, true, false) == Invoice.Approval.APPROVED)
                .map(i -> i.owedTotal(rate))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }

    /** The share of a HIGH-risk client's draft's grand total its deposits must cover before it is sent. */
    private static final BigDecimal DEPOSIT_SHARE = new BigDecimal("0.30");

    /**
     * Why the draft of a HIGH payment risk client cannot be sent yet, or null when it can: the deposits
     * held for it, at what they are worth in the client's currency (after any conversion fee or card
     * surcharge), must add up to at least 30% of its grand total.
     */
    private String depositRefusal(Invoice invoice) {
        if (riskOf(invoice.getClientId()).band() != RiskBand.HIGH) {
            return null;
        }
        Map<String, Long> drafts = draftsByReference(invoice.getClientId());
        BigDecimal held = payments.findForClient(invoice.getClientId()).stream()
                .filter(p -> invoice.getId().equals(heldFor(p, drafts)))
                .map(Payment::credit).filter(c -> c.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal needed = invoice.total(taxRate.percent()).multiply(DEPOSIT_SHARE)
                .setScale(invoice.scale(), RoundingMode.CEILING);
        if (held.compareTo(needed) >= 0) {
            return null;
        }
        Currency currency = invoice.currency();
        return "The client pays slowly (HIGH payment risk): deposits of at least " + currency.format(needed)
                + " (30% of the grand total) must be held for this invoice before it is sent; "
                + currency.format(held) + " is held.";
    }

    /**
     * Why sending the draft would take its client over its credit limit, or null when it would not or the
     * client has no limit: the client's balance less what is in dispute (see {@link #creditBalance}) plus what its other
     * approved drafts will add (see {@link #committed}) plus what the invoice adds to it (its grand total, priced
     * as it would be sent, less the withholding) must not be over the limit.
     */
    private String creditLimitRefusal(Invoice invoice) {
        Client client = clients.findById(invoice.getClientId()).orElse(null);
        if (client == null || client.getCreditLimit() == null) {
            return null;
        }
        repriceFromRates(invoice, null, today.date());
        BigDecimal after = creditBalance(client.getId()).add(committed(client.getId(), invoice.getId()))
                .add(invoice.owedTotal(taxRate.percent()));
        BigDecimal limit = creditLimitOf(client);
        if (after.compareTo(limit) <= 0) {
            return null;
        }
        Currency currency = client.getCurrency();
        return "Sending this invoice would take " + client.getName() + "'s balance (less what is in dispute, plus its approved drafts) to " + currency.format(after)
                + ", over its " + currency.format(limit) + " credit limit"
                + (limit.compareTo(client.getCreditLimit()) == 0 ? "." : " (half of its " + currency.format(client.getCreditLimit()) + " limit, as a HIGH payment risk).");
    }

    /**
     * Why the draft credit note cannot be issued, or null when it can: its original must be a sent
     * invoice (not void).
     */
    private String creditNoteRefusal(Invoice note) {
        return creditNoteRefusal(note, true);
    }

    /** As {@link #creditNoteRefusal(Invoice)}, checking that it credits no more of a line than was invoiced only when asked. */
    private String creditNoteRefusal(Invoice note, boolean checkOverCredit) {
        Invoice original = load(note.getCreditOf()).orElse(null);
        if (original == null || original.isDraft() || original.isVoid()) {
            return "The invoice this credits is not a sent invoice, so the credit note cannot be issued.";
        }
        String overCredit = checkOverCredit ? overCreditRefusal(note, original) : null;
        if (overCredit != null) {
            return overCredit;
        }
        return null;
    }

    /**
     * Why the draft credit note would credit more of one of its original's lines than was invoiced,
     * counting the credit notes already issued against it, or null when it does not.
     */
    private String overCreditRefusal(Invoice note, Invoice original) {
        Map<Long, BigDecimal> inNote = creditedQty(List.of(note));
        if (inNote.isEmpty()) {
            return null;
        }
        Map<Long, BigDecimal> issued = creditedQty(loadForClient(original.getClientId()).stream()
                .filter(i -> i.getStatus() == InvoiceStatus.CREDIT_NOTE && original.getId().equals(i.getCreditOf()))
                .toList());
        for (InvoiceLine line : original.getLines()) {
            BigDecimal qty = inNote.get(line.getId());
            BigDecimal already = issued.getOrDefault(line.getId(), BigDecimal.ZERO);
            if (qty != null && qty.add(already).compareTo(line.getQty()) > 0) {
                return "The credit note cannot credit more of \"" + line.getDescription() + "\" than was invoiced: "
                        + line.getQty().stripTrailingZeros().toPlainString() + " invoiced, "
                        + already.stripTrailingZeros().toPlainString() + " already credited.";
            }
        }
        return null;
    }

    /** The quantity the credit notes credit of each line they credit, by the id of the line credited. */
    private static Map<Long, BigDecimal> creditedQty(List<Invoice> notes) {
        Map<Long, BigDecimal> credited = new HashMap<>();
        notes.forEach(note -> note.getLines().stream().filter(l -> l.getCreditOfLine() != null)
                .forEach(l -> credited.merge(l.getCreditOfLine(), l.getQty(), BigDecimal::add)));
        return credited;
    }

    /**
     * Issues the draft credit note today and records it in the audit: its grand total comes off what
     * its original still owes; any rest is held on the client's
     * account as a credit payment dated today, also recorded in the audit.
     */
    private void issue(Invoice note) {
        BigDecimal rate = taxRate.percent();
        Invoice original = load(note.getCreditOf()).orElseThrow();
        // Read again what it has been credited: another credit note may just have been issued against it.
        entityManager.refresh(original);
        withInterest(List.of(original));
        BigDecimal total = note.total(rate);
        BigDecimal taken = original.creditTaken(total, paid(List.of(original)).get(original.getId()), rate);
        note.markIssued(today.date(), rate, taken);
        invoices.saveAndFlush(note);
        if (note.isAdjustment()) {
            audit.record("ADJUSTMENT_ISSUED id=" + note.getId() + " number=" + note.number() + " invoice="
                    + note.getCreditOf() + " total=" + total.negate().toPlainString());
        } else {
            audit.record("CREDIT_NOTE_ISSUED id=" + note.getId() + " number=" + note.number() + " invoice="
                    + note.getCreditOf() + " total=" + total.toPlainString());
        }
        BigDecimal rest = total.subtract(taken);
        if (rest.signum() > 0) {
            recordCreditHeld(payments.saveAndFlush(Payment.fromCreditNote(note, rest)));
        }
    }

    /**
     * Issues the draft today, due on the client's terms, and records it in the audit (INVOICE_SENT, or
     * ADJUSTMENT_ISSUED for a debit adjustment note); then uses the
     * client's credit on it (see {@link #applyCredit}).
     */
    private void markSent(Invoice invoice) {
        LocalDate issued = today.date();
        // An adjustment note is priced as of the invoice it adjusts.
        repriceFromRates(invoice, null, invoice.isAdjustment() ? invoice.pricingDate() : issued);
        invoice.markSent(issued, cappedForRisk(invoice.getClientId(), issued, dueDateFor(invoice.getClientId(), issued)));
        invoice.keepSentFigures(taxRate.percent());
        invoices.saveAndFlush(invoice);
        if (invoice.isAdjustment()) {
            audit.record("ADJUSTMENT_ISSUED id=" + invoice.getId() + " number=" + invoice.number() + " invoice="
                    + invoice.getAdjustmentOf() + " total=" + invoice.total(taxRate.percent()).toPlainString());
        } else {
            audit.record("INVOICE_SENT id=" + invoice.getId() + " number=" + invoice.number()
                    + " total=" + invoice.total(taxRate.percent()).toPlainString());
        }
        invoice.taxByRate(taxRate.percent()).forEach((rate, amounts) -> audit.record("TAX_CHARGED id="
                + invoice.getId() + " rate=" + rate.stripTrailingZeros().toPlainString() + " net="
                + amounts[0].toPlainString() + " tax=" + amounts[1].toPlainString()));
        if (invoice.isReverseCharge()) {
            audit.record("REVERSE_CHARGE id=" + invoice.getId() + " number=" + invoice.number()
                    + " total=" + invoice.total(taxRate.percent()).toPlainString());
        }
        if (invoice.isAdjustment()) {
            // A debit note's withholding is judged together with its invoice and the other notes.
            withInterest(List.of(invoice));
        }
        BigDecimal withheld = invoice.withholding(taxRate.percent());
        if (withheld.signum() > 0) {
            audit.record("WITHHOLDING id=" + invoice.getId() + " amount=" + withheld.toPlainString());
        }
        applyCredit(invoice);
    }

    /**
     * Uses the client's credit on the invoice just sent, up to what it has due: deposits held for it
     * first, then the oldest payment's credit (lower id first on the same date), never a deposit held
     * for another of the client's invoices (see {@link #holdersByReference}); each part applied today and recorded in the audit. Credit
     * used counts as paying early: while the early window is open only what settles it early is taken,
     * and when the credit covers that the invoice is settled with the early discount, as an early payment is.
     */
    private void applyCredit(Invoice invoice) {
        BigDecimal paidSoFar = paid(List.of(invoice)).get(invoice.getId());
        BigDecimal due = invoice.amountDue(paidSoFar, taxRate.percent());
        LocalDate now = today.date();
        boolean used = false;
        Map<String, Long> drafts = holdersByReference(invoice.getClientId());
        drafts.put(PaymentReference.of(invoice.getId()), invoice.getId());
        List<Payment> usable = new ArrayList<>();
        List<Payment> others = new ArrayList<>();
        for (Payment payment : payments.findForClient(invoice.getClientId())) {
            Long heldFor = heldFor(payment, drafts);
            if (invoice.getId().equals(heldFor)) {
                usable.add(payment);
            } else if (heldFor == null) {
                others.add(payment);
            }
        }
        usable.addAll(others);
        BigDecimal early = BigDecimal.ZERO;
        invoice.withholdEarlyPay(bandOfOthers(invoice) != RiskBand.LOW);
        BigDecimal settle = invoice.settleAmount(paidSoFar, taxRate.percent(), now);
        if (settle != null && usable.stream().map(Payment::credit).filter(c -> c.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(settle) >= 0) {
            early = invoice.earlyDiscount(taxRate.percent());
            invoice.takeEarlyDiscount(early);
            due = settle;
        }
        for (Payment payment : usable) {
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
        if (early.signum() > 0) {
            invoices.saveAndFlush(invoice);
            audit.record("EARLY_DISCOUNT id=" + invoice.getId() + " amount=" + early.toPlainString());
        }
        if (used) {
            recordWriteOff(invoice);
        }
    }

    /**
     * Records a deposit of the amount on the date from the client: the whole amount is held on the
     * client's account as credit, applied to no invoice, and recorded in the audit; empty if no such
     * client. With a reference (null when none, already checked) it is held for that one of the
     * client's drafts (see {@link #heldFor}); a reference that is not one of its drafts' is refused.
     * The amount has already been checked; a date after today is refused.
     */
    @Transactional
    public Optional<Payment> recordDeposit(Long clientId, BigDecimal paidAmount, Currency paidIn, LocalDate date,
            String reference, PaymentMethod method) {
        Optional<Client> client = clients.findById(clientId);
        if (client.isEmpty()) {
            return Optional.empty();
        }
        if (reference != null && !draftsByReference(clientId).containsKey(reference)) {
            throw new InvoiceRejectedException(PaymentReference.grouped(reference)
                    + " is not the reference of any of this client's drafts, so the deposit cannot be held for it.");
        }
        Currency currency = client.get().getCurrency();
        BigDecimal amount = converted(paidAmount, paidIn, currency, date);
        LocalDate now = today.date();
        if (date.isAfter(now)) {
            throw new InvoiceRejectedException("The payment date cannot be after today (" + now + ").");
        }
        BigDecimal surcharge = surchargeOf(amount, currency, method, date);
        BigDecimal fee = feeOf(amount, paidIn, currency);
        Payment payment = payments.saveAndFlush(Payment.deposit(clientId, amount, date, reference).asDeposit()
                .paidIn(paidIn, paidAmount, currency).by(method).surcharged(surcharge).feeCharged(fee));
        recordFee(payment, currency);
        recordSurcharge(payment, currency);
        audit.record("DEPOSIT_RECORDED id=" + payment.getId() + " client=" + clientId + " amount="
                + amount.subtract(surcharge).subtract(fee).setScale(currency.scale(), RoundingMode.UNNECESSARY).toPlainString());
        return Optional.of(payment);
    }

    /**
     * Refunds the amount of the client's unused credit on the date, never a deposit held for one of its
     * drafts. The amount is in the currency refunded in (null for the client's own) and comes off the
     * credit converted into the client's currency through US dollars at the rates on the date, rounded down
     * to their currency's step: the oldest payment's credit first
     * (lower id first on the same date), then the next, each part recorded in the audit; empty if no
     * such client. The amount has already been checked; a date after today or before a payment it
     * would come from, or an amount more than the client's credit, is refused.
     */
    @Transactional
    public Optional<Long> refundCredit(Long clientId, BigDecimal refundAmount, Currency refundIn, LocalDate date) {
        Optional<Client> client = clients.findById(clientId);
        if (client.isEmpty()) {
            return Optional.empty();
        }
        Currency currency = client.get().getCurrency();
        Currency refundCurrency = refundIn == null ? currency : refundIn;
        requireWhole(refundCurrency, refundAmount, "The refund");
        BigDecimal amount = exchangeRates.convert(refundAmount, refundCurrency, currency, date);
        if (amount.signum() <= 0) {
            throw new InvoiceRejectedException("The refund is worth nothing once converted into " + currency.name() + ".");
        }
        LocalDate now = today.date();
        if (date.isAfter(now)) {
            throw new InvoiceRejectedException("The refund date cannot be after today (" + now + ").");
        }
        List<Payment> from = new ArrayList<>();
        List<BigDecimal> parts = new ArrayList<>();
        BigDecimal left = amount;
        BigDecimal credit = BigDecimal.ZERO;
        Map<String, Long> drafts = holdersByReference(clientId);
        for (Payment payment : payments.findForClient(clientId)) {
            if (heldFor(payment, drafts) != null) {
                continue;
            }
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
     * Voids a sent invoice paid nothing but credit used on it when it was sent, and records it in the
     * audit; that credit goes back on the client's account, unused again, each payment's part recorded
     * in the audit. Empty if no such invoice. A draft, an invoice already void, or one with a payment
     * recorded against it is refused.
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
            if (invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("A written-off invoice cannot be voided.");
            }
            if (invoice.hasCreditNotesIssued()) {
                throw new InvoiceRejectedException("An invoice with an issued credit note cannot be voided.");
            }
            List<Payment> paidBy = payments.findForInvoice(invoice.getId());
            if (paidBy.stream().anyMatch(payment -> !payment.onlyCreditUsedOn(invoice.getId()))) {
                throw new InvoiceRejectedException("An invoice with a payment recorded against it cannot be voided.");
            }
            invoice.markVoid();
            invoices.saveAndFlush(invoice);
            for (Payment payment : paidBy) {
                BigDecimal returned = payment.returnCredit(invoice.getId());
                payments.saveAndFlush(payment);
                audit.record("CREDIT_RETURNED payment=" + payment.getId() + " amount="
                        + returned.setScale(invoice.scale(), RoundingMode.UNNECESSARY).toPlainString());
            }
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
     * already been checked; one that belongs to a different invoice is refused. Cash (the method; null
     * for a bank transfer) must be in the client's own currency and a whole number of its coins; paying
     * exactly what is due rounded to them settles the invoice, the difference its cash rounding.
     * A payment reaching what is in dispute accepts it: the dispute is resolved on the payment date and
     * recorded in the audit, the interest it held back owed again, and the payment is applied as usual.
     */
    @Transactional
    public Optional<Invoice> recordPayment(Long invoiceId, BigDecimal paidAmount, Currency paidIn, LocalDate date,
            String reference, PaymentMethod method, String remittance) {
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
            if (invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("A written-off invoice takes no more payments.");
            }
            boolean cash = method == PaymentMethod.CASH;
            if (cash && paidIn != null && paidIn != invoice.currency()) {
                throw new InvoiceRejectedException("Cash is paid only in the client's own currency ("
                        + invoice.currency() + ").");
            }
            if (cash && !invoice.currency().isCash(paidAmount)) {
                throw new InvoiceRejectedException("Cash must be counted out in "
                        + invoice.currency().format(invoice.currency().cashStep()) + " coins.");
            }
            if (onHold(invoice.getClientId())) {
                payOverdueThenHold(invoice.getClientId(), paidAmount, paidIn, date, method).remitted(remittance);
                return;
            }
            BigDecimal received = converted(paidAmount, paidIn, invoice.currency(), date);
            // Only what is left once the conversion fee and card surcharge are taken out pays the invoice.
            BigDecimal surcharge = surchargeOf(received, invoice.currency(), method, date);
            BigDecimal fee = feeOf(received, paidIn, invoice.currency());
            BigDecimal amount = received.subtract(surcharge).subtract(fee);
            BigDecimal paidSoFar = paid(List.of(invoice)).get(invoice.getId());
            LocalDate now = today.date();
            // A payment reaching what is in dispute accepts it: the dispute is resolved that day, and the
            // interest it held back is owed again.
            boolean resolved = invoice.isDisputed() && !date.isAfter(now)
                    && amount.compareTo(invoice.amountDue(paidSoFar, taxRate.percent())
                            .subtract(invoice.disputedAmount(paidSoFar, taxRate.percent()))) > 0;
            if (resolved) {
                invoice.resolveDispute(invoice.getDisputedOn() != null && date.isBefore(invoice.getDisputedOn())
                        ? invoice.getDisputedOn() : date);
                invoices.saveAndFlush(invoice);
                withInterest(List.of(invoice));
            }
            BigDecimal due = invoice.amountDue(paidSoFar, taxRate.percent());
            if (due.signum() <= 0) {
                throw new InvoiceRejectedException("This invoice has already been paid.");
            }
            BigDecimal early = BigDecimal.ZERO;
            BigDecimal settle = date.isAfter(now) ? null : invoice.settleAmount(paidSoFar, taxRate.percent(), date);
            if (settle != null && amount.compareTo(settle) >= 0) {
                early = invoice.earlyDiscount(taxRate.percent());
                invoice.takeEarlyDiscount(early);
                due = settle;
            }
            BigDecimal rounding = BigDecimal.ZERO;
            if (cash && amount.compareTo(invoice.currency().toCash(due)) == 0) {
                rounding = amount.subtract(due);
            }
            BigDecimal applied = amount.min(due.add(rounding));
            if (invoice.isTooSmallPart(applied, paidSoFar, taxRate.percent())) {
                throw new InvoiceRejectedException("A part payment must be at least "
                        + invoice.currency().format(invoice.minimumPart(paidSoFar, taxRate.percent()))
                        + " (10% of what it still has due), unless it settles the invoice.");
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
            if (rounding.signum() != 0) {
                invoice.roundForCash(rounding);
                invoices.saveAndFlush(invoice);
            }
            if (resolved) {
                audit.record("DISPUTE_RESOLVED id=" + invoice.getId());
            }
            Payment against = Payment.against(invoice, received, applied, date, reference)
                    .paidIn(paidIn, paidAmount, invoice.currency()).by(method).surcharged(surcharge).feeCharged(fee)
                    .remitted(remittance);
            // What is left after the invoice pays its debit adjustment notes, oldest first, before any is held as credit.
            BigDecimal rest = amount.subtract(applied);
            Map<Invoice, BigDecimal> notesPaid = new LinkedHashMap<>();
            if (rest.signum() > 0) {
                for (Map.Entry<Invoice, BigDecimal> note : debitNotesOwing(invoice).entrySet()) {
                    if (rest.signum() <= 0) {
                        break;
                    }
                    if (note.getKey().getIssueDate() != null && date.isBefore(note.getKey().getIssueDate())) {
                        continue;
                    }
                    BigDecimal part = rest.min(note.getValue());
                    against.alsoPays(note.getKey().getId(), part);
                    notesPaid.put(note.getKey(), part);
                    rest = rest.subtract(part);
                }
            }
            Payment payment = payments.saveAndFlush(against);
            recordFee(payment, invoice.currency());
            recordSurcharge(payment, invoice.currency());
            audit.record("PAYMENT_RECORDED id=" + payment.getId() + " invoice=" + invoice.getId() + " amount="
                    + applied.setScale(invoice.scale(), RoundingMode.UNNECESSARY).toPlainString());
            if (rounding.signum() != 0) {
                audit.record("CASH_ROUNDING id=" + invoice.getId() + " amount="
                        + rounding.setScale(invoice.scale(), RoundingMode.UNNECESSARY).toPlainString());
            }
            if (invoice.currency() != Currency.USD) {
                audit.record("FX_DIFFERENCE id=" + invoice.getId() + " payment=" + payment.getId() + " amount="
                        + invoice.fxDifference(applied, date).toPlainString());
            }
            if (early.signum() > 0) {
                invoices.saveAndFlush(invoice);
                audit.record("EARLY_DISCOUNT id=" + invoice.getId() + " amount=" + early.toPlainString());
            }
            recordWriteOff(invoice);
            notesPaid.forEach((note, part) -> {
                audit.record("PAYMENT_RECORDED id=" + payment.getId() + " invoice=" + note.getId() + " amount="
                        + part.setScale(note.scale(), RoundingMode.UNNECESSARY).toPlainString());
                recordWriteOff(note);
            });
            recordCreditHeld(payment);
        });
        return found;
    }

    /**
     * Records one payment of the amount on the date against the client's unpaid sent invoices: the overdue
     * ones first, earliest due date first (lower id first on the same due date), then the rest from the
     * smallest amount due to the largest (lower id first on the same amount), paying each off in full
     * before the next; then what is in dispute (see {@link #inDisputeOrder}), which stays in dispute.
     * What it pays beyond everything the client owes is held on the client's account as credit; the
     * rest is checked and recorded as a split payment over the invoices it reaches.
     * References (empty when none, each already checked) instead limit the payment to the client's
     * invoices with those references, applied in the same order; one that is not the reference of any
     * of the client's invoices is refused; what is paid beyond what those invoices owe together is held
     * as credit.
     * A reference may state the amount it gets: that amount goes to that invoice, and only the rest of
     * the payment is applied, in the same order, to the references without one. The amounts stated must
     * each be more than zero and together no more than the payment; each must be no more than its
     * invoice has due and at least its minimum part payment (both checked with the other parts).
     * The payment keeps the reference of the first invoice it reaches.
     * With no reference, remittance text (null or blank when none) first pays, for each of its parts separated
     * by semicolons in turn, the open invoice not matched by an earlier part whose purchase order number is
     * most like it: a Jaro-Winkler similarity (see {@link RemittanceMatch}) of at least 0.90,
     * the larger amount due, then the earlier due date, then the lower id on a tie, among invoices that take payments and have something due. The rest goes in the same order.
     */
    @Transactional
    public Payment recordAutoPayment(Long clientId, BigDecimal paidAmount, Currency paidIn, LocalDate date,
            List<QuotedReference> references, PaymentMethod method, String remittance) {
        if (onHold(clientId)) {
            return payments.saveAndFlush(payOverdueThenHold(clientId, paidAmount, paidIn, date, method).remitted(remittance));
        }
        Currency currency = currencyOf(clientId);
        BigDecimal received = converted(paidAmount, paidIn, currency, date);
        // Only what is left once the conversion fee and card surcharge are taken out pays invoices or is held as credit.
        BigDecimal surcharge = surchargeOf(received, currency, method, date);
        BigDecimal fee = feeOf(received, paidIn, currency);
        BigDecimal amount = received.subtract(surcharge).subtract(fee);
        List<Invoice> ofClient = loadForClient(clientId);
        List<Invoice> candidates = ofClient;
        List<PaymentAllocation> parts = new ArrayList<>();
        BigDecimal left = amount;
        // The invoices whose amounts were stated; the rest of the payment is applied automatically.
        Set<Long> stated = new HashSet<>();
        if (!references.isEmpty()) {
            Set<Long> named = new HashSet<>();
            for (QuotedReference quoted : references) {
                String reference = quoted.reference();
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
                if (invoice.onlyDisputedDue(paid(List.of(invoice)).get(invoice.getId()), taxRate.percent())) {
                    throw new InvoiceRejectedException(number + " is in dispute and takes no payments.");
                }
                if (quoted.amount() == null) {
                    named.add(invoice.getId());
                    continue;
                }
                if (quoted.amount().signum() <= 0) {
                    throw new InvoiceRejectedException("The amount for " + number + " must be more than zero.");
                }
                if (!stated.add(invoice.getId())) {
                    throw new InvoiceRejectedException("State only one amount for " + number + ".");
                }
                parts.add(new PaymentAllocation(invoice.getId(), quoted.amount()));
                left = left.subtract(quoted.amount());
            }
            if (left.signum() < 0) {
                BigDecimal sum = amount.subtract(left);
                throw new InvoiceRejectedException("The amounts stated for the invoices add up to "
                        + currency.format(sum) + ", more than the " + currency.format(amount) + " paid.");
            }
            named.removeAll(stated);
            // A named invoice's debit adjustment notes are paid with it.
            Set<Long> referenced = new HashSet<>(named);
            referenced.addAll(stated);
            candidates = ofClient.stream().filter(i -> named.contains(i.getId())
                    || (isDebitNote(i) && referenced.contains(i.getAdjustmentOf()) && !stated.contains(i.getId())))
                    .toList();
        }
        Map<Long, BigDecimal> dues = new HashMap<>();
        List<Invoice> sent = inPaymentOrder(candidates, dues);
        // Each part of the remittance (separated by semicolons) in turn matches an invoice not matched by an earlier part.
        List<Invoice> matched = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        if (references.isEmpty() && remittance != null && !remittance.isBlank()) {
            for (String part : remittance.split(";")) {
                if (part.isBlank()) {
                    continue;
                }
                Invoice best = null;
                double score = 0;
                for (Invoice invoice : sent) {
                    if (dues.get(invoice.getId()).signum() <= 0 || invoice.getPoNumber() == null || matched.contains(invoice)) {
                        continue;
                    }
                    double similarity = RemittanceMatch.similarity(part.trim(), invoice.getPoNumber());
                    if (similarity < RemittanceMatch.THRESHOLD) {
                        continue;
                    }
                    if (best == null || similarity > score || (similarity == score && earlierMatch(invoice, best, dues))) {
                        best = invoice;
                        score = similarity;
                    }
                }
                if (best != null) {
                    matched.add(best);
                    scores.add(score);
                }
            }
            if (!matched.isEmpty()) {
                List<Invoice> reordered = new ArrayList<>(matched);
                sent.stream().filter(i -> !matched.contains(i)).forEach(reordered::add);
                sent = reordered;
            }
        }
        for (Invoice invoice : sent) {
            BigDecimal due = dues.get(invoice.getId());
            if (due.signum() <= 0) {
                continue;
            }
            if (left.signum() > 0) {
                BigDecimal part = left.min(due);
                parts.add(new PaymentAllocation(invoice.getId(), part));
                left = left.subtract(part);
            }
        }
        // What is left goes to what is in dispute, which stays in dispute, before any of it is held as credit; the
        // disputed part of a partly disputed invoice is a part of its own, after its undisputed part.
        for (Map.Entry<Invoice, BigDecimal> disputed : inDisputeOrder(candidates).entrySet()) {
            if (left.signum() <= 0) {
                break;
            }
            Long invoiceId = disputed.getKey().getId();
            if (stated.contains(invoiceId)) {
                continue;
            }
            BigDecimal part = left.min(disputed.getValue());
            parts.add(new PaymentAllocation(invoiceId, part));
            left = left.subtract(part);
        }
        if (parts.isEmpty()) {
            throw new InvoiceRejectedException(references.isEmpty() ? "The client has no unpaid invoices to pay."
                    : "Those invoices have already been paid.");
        }
        String reference = references.isEmpty() ? null : PaymentReference.of(parts.get(0).getInvoiceId());
        Payment payment = recordSpread(clientId, amount, paidAmount, paidIn, date, reference, parts, method, surcharge, fee,
                        stated)
                .remitted(remittance);
        for (int i = 0; i < matched.size(); i++) {
            payment.matched(matched.get(i).getId(), scores.get(i));
        }
        return payments.saveAndFlush(payment);
    }

    /**
     * Of two equally good remittance matches, whether this one is taken first: the larger amount due, then the earlier
     * due date, then the lower id.
     */
    private static boolean earlierMatch(Invoice invoice, Invoice than, Map<Long, BigDecimal> dues) {
        Comparator<Invoice> order = Comparator.comparing((Invoice i) -> dues.get(i.getId()), Comparator.reverseOrder())
                .thenComparing(Invoice::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Invoice::getId);
        return order.compare(invoice, than) < 0;
    }

    /**
     * The sent invoices among these (not drafts, void invoices, credit notes or invoices in dispute) in the payment order:
     * the overdue ones first, earliest due date first, then the rest from the smallest amount due to the
     * largest; lower id first on a tie. Fills in what each has due.
     */
    private List<Invoice> inPaymentOrder(List<Invoice> candidates, Map<Long, BigDecimal> dues) {
        List<Invoice> unordered = candidates.stream()
                .filter(i -> !i.isDraft() && !i.isVoid() && !i.isCreditNote())
                .toList();
        Map<Long, BigDecimal> allPaid = paid(unordered);
        unordered = unordered.stream()
                .filter(i -> !i.onlyDisputedDue(allPaid.get(i.getId()), taxRate.percent())).toList();
        Map<Long, BigDecimal> paid = allPaid;
        LocalDate now = today.date();
        Set<Long> overdue = new HashSet<>();
        for (Invoice invoice : unordered) {
            BigDecimal invoicePaid = paid.get(invoice.getId());
            dues.put(invoice.getId(), invoice.amountDue(invoicePaid, taxRate.percent())
                    .subtract(invoice.disputedAmount(invoicePaid, taxRate.percent())));
            if (invoice.statusWhenPaid(invoicePaid, taxRate.percent(), now) == InvoiceStatus.OVERDUE) {
                overdue.add(invoice.getId());
            }
        }
        // Overdue first, oldest due date first; then the rest, smallest amount due first; lower id on a tie.
        Comparator<Invoice> byPriority = Comparator.<Invoice, Boolean>comparing(i -> !overdue.contains(i.getId()))
                .thenComparing(i -> overdue.contains(i.getId()) ? i.getDueDate() : null,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(i -> overdue.contains(i.getId()) ? BigDecimal.ZERO : dues.get(i.getId()))
                .thenComparing(Invoice::getId);
        return notesAfterTheirInvoices(unordered.stream().sorted(byPriority).toList());
    }

    /**
     * The invoices in the same order, except that each debit adjustment note whose invoice is among them comes
     * straight after that invoice, oldest first (lower id first on the same issue date), whatever its own place.
     */
    private static List<Invoice> notesAfterTheirInvoices(List<Invoice> ordered) {
        Set<Long> ids = new HashSet<>();
        ordered.forEach(i -> ids.add(i.getId()));
        Map<Long, List<Invoice>> notes = new HashMap<>();
        ordered.stream()
                .filter(i -> isDebitNote(i) && ids.contains(i.getAdjustmentOf()))
                .sorted(oldestFirst())
                .forEach(i -> notes.computeIfAbsent(i.getAdjustmentOf(), k -> new ArrayList<>()).add(i));
        List<Invoice> result = new ArrayList<>();
        for (Invoice invoice : ordered) {
            if (isDebitNote(invoice) && ids.contains(invoice.getAdjustmentOf())) {
                continue;
            }
            result.add(invoice);
            result.addAll(notes.getOrDefault(invoice.getId(), List.of()));
        }
        return result;
    }

    /** Whether the invoice is a debit adjustment note: one adjusting a sent invoice that is not a credit note. */
    private static boolean isDebitNote(Invoice invoice) {
        return invoice.isAdjustment() && !invoice.isCreditNote();
    }

    private static Comparator<Invoice> oldestFirst() {
        return Comparator.comparing(Invoice::getIssueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Invoice::getId);
    }

    /**
     * The invoice's debit adjustment notes that still have something due (in dispute left out), oldest first, with
     * what each has due.
     */
    private Map<Invoice, BigDecimal> debitNotesOwing(Invoice invoice) {
        List<Invoice> notes = loadForClient(invoice.getClientId()).stream()
                .filter(i -> isDebitNote(i) && invoice.getId().equals(i.getAdjustmentOf()) && !i.isDraft()
                        && !i.isVoid() && !i.isWrittenOff())
                .sorted(oldestFirst())
                .toList();
        Map<Long, BigDecimal> paid = paid(notes);
        Map<Invoice, BigDecimal> owing = new LinkedHashMap<>();
        for (Invoice note : notes) {
            BigDecimal notePaid = paid.get(note.getId());
            BigDecimal due = note.amountDue(notePaid, taxRate.percent())
                    .subtract(note.disputedAmount(notePaid, taxRate.percent()));
            if (due.signum() > 0) {
                owing.put(note, due);
            }
        }
        return owing;
    }

    /**
     * What is in dispute on the sent invoices among these (see {@link Invoice#disputedAmount}), by invoice, in the
     * order it is paid once everything else is: oldest due date first, lower id on a tie.
     */
    private Map<Invoice, BigDecimal> inDisputeOrder(List<Invoice> candidates) {
        List<Invoice> disputed = candidates.stream()
                .filter(i -> !i.isDraft() && !i.isVoid() && !i.isCreditNote() && i.isDisputed())
                .sorted(Comparator.comparing(Invoice::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Invoice::getId))
                .toList();
        Map<Long, BigDecimal> paid = paid(disputed);
        Map<Invoice, BigDecimal> amounts = new LinkedHashMap<>();
        for (Invoice invoice : disputed) {
            BigDecimal amount = invoice.disputedAmount(paid.get(invoice.getId()), taxRate.percent());
            if (amount.signum() > 0) {
                amounts.put(invoice, amount);
            }
        }
        return amounts;
    }

    /**
     * Uses the client's unused credit now on what they owe: the oldest payment's credit first (lower id
     * first on the same date), never a deposit held for one of the client's drafts, spread over their
     * sent invoices in the payment order (see {@link #inPaymentOrder}), each paid off before the next, then
     * over what is in dispute (see {@link #inDisputeOrder}), which stays in dispute; each part applied today and recorded in the audit. Empty if no such client. Refused when the
     * client has no credit to use or owes nothing.
     */
    @Transactional
    public Optional<Long> applyCreditNow(Long clientId) {
        Optional<Client> client = clients.findById(clientId);
        if (client.isEmpty()) {
            return Optional.empty();
        }
        if (client.get().isOnHold() || longDispute(clientId)) {
            throw new InvoiceRejectedException("The client is on hold: lift the hold before applying their credit.");
        }
        Map<String, Long> drafts = holdersByReference(clientId);
        List<Payment> usable = payments.findForClient(clientId).stream()
                .filter(p -> heldFor(p, drafts) == null && p.credit().signum() > 0)
                .toList();
        if (usable.isEmpty()) {
            throw new InvoiceRejectedException("The client has no unused credit to apply.");
        }
        Map<Long, BigDecimal> dues = new HashMap<>();
        List<Invoice> ofClient = loadForClient(clientId);
        // What is in dispute is paid last, and stays in dispute.
        List<Map.Entry<Invoice, BigDecimal>> owing = new ArrayList<>();
        inPaymentOrder(ofClient, dues).stream()
                .filter(i -> dues.get(i.getId()).signum() > 0)
                .forEach(i -> owing.add(Map.entry(i, dues.get(i.getId()))));
        owing.addAll(inDisputeOrder(ofClient).entrySet());
        if (owing.isEmpty()) {
            throw new InvoiceRejectedException("The client owes nothing to apply credit to.");
        }
        LocalDate now = today.date();
        Set<Invoice> reached = new LinkedHashSet<>();
        Iterator<Payment> from = usable.iterator();
        Payment payment = from.next();
        BigDecimal credit = payment.credit();
        for (Map.Entry<Invoice, BigDecimal> owed : owing) {
            Invoice invoice = owed.getKey();
            BigDecimal due = owed.getValue();
            while (due.signum() > 0 && credit != null) {
                BigDecimal part = credit.min(due);
                payment.apply(invoice.getId(), part, now);
                payments.saveAndFlush(payment);
                audit.record("PAYMENT_RECORDED id=" + payment.getId() + " invoice=" + invoice.getId() + " amount="
                        + part.setScale(invoice.scale(), RoundingMode.UNNECESSARY).toPlainString());
                reached.add(invoice);
                due = due.subtract(part);
                credit = credit.subtract(part);
                if (credit.signum() <= 0) {
                    payment = from.hasNext() ? from.next() : null;
                    credit = payment == null ? null : payment.credit();
                }
            }
            if (credit == null) {
                break;
            }
        }
        reached.forEach(this::recordWriteOff);
        return Optional.of(clientId);
    }

    /**
     * Records one payment of the amount on the date spread over several of the client's invoices, and
     * records each part in the audit. The amounts have already been checked to be more than zero; they
     * must add up to no more than the payment: the rest is
     * held on the client's account as credit and recorded in the audit. The parts to each invoice (at most an undisputed and a disputed part) must go to a
     * sent invoice of the client and be no more than what it still has due. The date must be no later
     * than today nor before any of the invoices' issue dates. Only the parts going to the stated invoices
     * must be at least their minimum part payment. The reference (null when none) has
     * already been checked; it must be the reference of one of the invoices.
     */
    private Payment recordSpread(Long clientId, BigDecimal amount, BigDecimal paidAmount, Currency paidIn,
            LocalDate date, String reference, List<PaymentAllocation> parts, PaymentMethod method,
            BigDecimal surcharge, BigDecimal fee, Set<Long> stated) {
        if (parts.isEmpty()) {
            throw new InvoiceRejectedException("Enter how much of the payment goes to each invoice.");
        }
        Currency currency = currencyOf(clientId);
        requireWhole(currency, amount, "The amount");
        for (PaymentAllocation part : parts) {
            requireWhole(currency, part.getAmount(), "Each invoice's part");
        }
        BigDecimal sum = parts.stream().map(PaymentAllocation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(amount) > 0) {
            throw new InvoiceRejectedException("The amounts for the invoices add up to "
                    + currency.format(sum) + ", not the " + currency.format(amount) + " paid.");
        }
        LocalDate now = today.date();
        if (date.isAfter(now)) {
            throw new InvoiceRejectedException("The payment date cannot be after today (" + now + ").");
        }
        // An invoice may take two parts (its undisputed part, then its disputed part); they are checked together.
        Map<Long, BigDecimal> toInvoice = new LinkedHashMap<>();
        for (PaymentAllocation part : parts) {
            toInvoice.merge(part.getInvoiceId(), part.getAmount(), BigDecimal::add);
        }
        List<Invoice> spreadOver = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : toInvoice.entrySet()) {
            PaymentAllocation part = new PaymentAllocation(entry.getKey(), entry.getValue());
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
            // Only a stated amount must be at least the minimum part: what is applied automatically is whatever is left.
            boolean checked = stated.contains(invoice.getId());
            if (checked && invoice.isTooSmallPart(part.getAmount(), paidSoFar, taxRate.percent())) {
                throw new InvoiceRejectedException("The " + currency.format(part.getAmount()) + " for "
                        + number + " is less than " + currency.format(invoice.minimumPart(paidSoFar, taxRate.percent()))
                        + " (10% of what it still has due), unless it settles the invoice.");
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
        Payment payment = payments.saveAndFlush(Payment.split(clientId, amount.add(surcharge).add(fee), date, reference, parts)
                .paidIn(paidIn, paidAmount, currency).by(method).surcharged(surcharge).feeCharged(fee));
        recordFee(payment, currency);
        recordSurcharge(payment, currency);
        for (PaymentAllocation part : parts) {
            audit.record("PAYMENT_RECORDED id=" + payment.getId() + " invoice=" + part.getInvoiceId() + " amount="
                    + part.getAmount().setScale(currency.scale(), RoundingMode.UNNECESSARY).toPlainString());
        }
        if (currency != Currency.USD) {
            for (Invoice invoice : spreadOver) {
                audit.record("FX_DIFFERENCE id=" + invoice.getId() + " payment=" + payment.getId() + " amount="
                        + invoice.fxDifference(payment.amountTo(invoice.getId()), date).toPlainString());
            }
        }
        spreadOver.forEach(this::recordWriteOff);
        recordCreditHeld(payment);
        return payment;
    }

    /**
     * The amount paid in the currency (null for the client's own) converted into the client's currency
     * through US dollars at the workspace rates in force on the date paid (today when none is given);
     * this is what is applied and checked. The amount must be
     * whole in the currency it was paid in, and must still be more than zero once converted.
     */
    private BigDecimal converted(BigDecimal paidAmount, Currency paidIn, Currency clientCurrency, LocalDate date) {
        Currency from = paidIn == null ? clientCurrency : paidIn;
        requireWhole(from, paidAmount, "The amount");
        BigDecimal amount = exchangeRates.convert(paidAmount, from, clientCurrency, date == null ? today.date() : date);
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
    /** A line's unit price, and the tiers it is priced in (null when it has a single price). */
    private record LinePrice(BigDecimal unitPrice, String tiers, BigDecimal increment, BigDecimal minUnits, boolean fromCard) {

        LinePrice(BigDecimal unitPrice, String tiers, BigDecimal increment, BigDecimal minUnits) {
            this(unitPrice, tiers, increment, minUnits, false);
        }
    }

    /**
     * A line's price: the unit price entered, or else the client's rate for the line's unit type in force on the
     * day the invoice is priced as of (today for a draft, see {@link #ratesDate}) — a single price, or tiers starting from the first band — or,
     * when the card has none in force, the workspace price list's in force then, converted into the client's currency
     * (see {@link PriceList}). Refused when neither is there, and a single price with more decimals than the currency
     * has. Either way the line is billed in the increment, and at least the minimum units, the client's rate card has
     * for its unit type then, if any. A price from the rates follows them while the invoice is a draft.
     */
    private LinePrice linePrice(Invoice invoice, String unitType, BigDecimal unitPrice) {
        return linePrice(invoice, unitType, unitPrice, ratesDate(invoice));
    }

    /**
     * The day the invoice's lines are priced as of: today for a draft (not a credit note), which will be dated
     * today when sent; otherwise its issue date, or today when it has none.
     */
    private LocalDate ratesDate(Invoice invoice) {
        return (invoice.isDraft() && !invoice.isCreditNote()) || invoice.getIssueDate() == null ? today.date()
                : invoice.getIssueDate();
    }

    /** A line's price as {@link #linePrice(Invoice, String, BigDecimal)}, at the rates in force on the given date. */
    private LinePrice linePrice(Invoice invoice, String unitType, BigDecimal unitPrice, LocalDate on) {
        Client client = unitType == null ? null : clients.findById(invoice.getClientId()).orElse(null);
        ClientRate rate = client == null ? null : client.rateFor(unitType, on);
        BigDecimal increment = rate == null ? null : rate.getIncrement();
        BigDecimal minUnits = rate == null ? null : rate.getMinUnits();
        BigDecimal discountPct = client == null ? null : client.getRateDiscountPct();
        if (unitPrice != null) {
            requireWhole(invoice.currency(), unitPrice, "The unit price");
            return new LinePrice(unitPrice, null, increment, minUnits);
        }
        if (unitType == null) {
            throw new InvoiceRejectedException("Enter a unit price or a unit type.");
        }
        if (rate == null) {
            PriceList.Converted listed = priceList.priceFor(unitType, invoice.currency(), on);
            if (listed == null) {
                throw new InvoiceRejectedException("The client's rate card has no rate for " + unitType
                        + "; enter a unit price.");
            }
            return new LinePrice(RateDiscount.price(listed.unitPrice(), discountPct, invoice.currency()),
                    RateDiscount.tiers(listed.tiers(), discountPct, invoice.currency()), null, null, true);
        }
        RateTiers tiers = rate.tiers();
        if (tiers != null) {
            return new LinePrice(RateDiscount.price(tiers.firstPrice(), discountPct, invoice.currency()),
                    RateDiscount.tiers(tiers.format(), discountPct, invoice.currency()), increment, minUnits, true);
        }
        requireWhole(invoice.currency(), rate.getUnitPrice(), "The unit price");
        return new LinePrice(RateDiscount.price(rate.getUnitPrice(), discountPct, invoice.currency()), null,
                increment, minUnits, true);
    }

    private static void requireWhole(Currency currency, BigDecimal amount, String what) {
        if (amount != null && !currency.isWhole(amount)) {
            throw new InvoiceRejectedException(what + " must be in whole yen, with no decimals.");
        }
    }

    /** The RF reference of each of the client's drafts, to its invoice id. */
    private Map<String, Long> draftsByReference(Long clientId) {
        Map<String, Long> drafts = new HashMap<>();
        for (Invoice invoice : invoices.findByClientIdOrderByIdAsc(clientId)) {
            if (invoice.isDraft() && !invoice.isCreditNote()) {
                drafts.put(PaymentReference.of(invoice.getId()), invoice.getId());
            }
        }
        return drafts;
    }

    /**
     * The RF reference of each of the client's invoices a deposit is held for, to its invoice id: its
     * drafts, and its sent invoices billed by milestones that still have milestones to be reached (nothing
     * is owed on those until a milestone is reached, so a deposit held for one stays held for it).
     */
    private Map<String, Long> holdersByReference(Long clientId) {
        Map<String, Long> holders = draftsByReference(clientId);
        for (Invoice invoice : invoices.findByClientIdOrderByIdAsc(clientId)) {
            if (invoice.hasPendingMilestones() && !invoice.isWrittenOff()) {
                holders.put(PaymentReference.of(invoice.getId()), invoice.getId());
            }
        }
        return holders;
    }

    /**
     * The draft the payment is held for, or null when it is ordinary credit: a payment whose reference
     * (an RF reference or invoice number, as stored) is that of one of the client's drafts is a deposit
     * held for it, as is a payment moved onto the draft that replaces a rebilled invoice. Once that invoice has
     * been sent the deposit's rest is ordinary credit.
     */
    private static Long heldFor(Payment payment, Map<String, Long> drafts) {
        if (payment.getHeldFor() != null && drafts.containsValue(payment.getHeldFor())) {
            return payment.getHeldFor();
        }
        String reference = payment.getReference();
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String compact = reference.replaceAll("\\s", "").toUpperCase();
        if (compact.startsWith("INV")) {
            compact = InvoiceNumber.idOf(compact).map(PaymentReference::of).orElse(compact);
        }
        return drafts.get(compact);
    }

    /** The card surcharge in the amount received by the method (null for a bank transfer): 0 unless by card. */
    private BigDecimal surchargeOf(BigDecimal received, Currency currency, PaymentMethod method, LocalDate date) {
        return method == PaymentMethod.CARD
                ? cardSurcharge.of(received, currency, date == null ? today.date() : date) : BigDecimal.ZERO;
    }

    /**
     * The conversion fee in the amount received converted into the client's currency from the currency paid in
     * (null for the client's own): 0 unless paid in another currency.
     */
    private BigDecimal feeOf(BigDecimal received, Currency paidIn, Currency currency) {
        return paidIn != null && paidIn != currency ? fxFee.of(received, currency.scale()) : BigDecimal.ZERO;
    }

    /** Records in the audit the conversion fee a payment just recorded carried, if any. */
    private void recordFee(Payment payment, Currency currency) {
        if (payment.getFxFee().signum() > 0) {
            audit.record("FX_FEE payment=" + payment.getId() + " amount="
                    + payment.getFxFee().setScale(currency.scale(), RoundingMode.UNNECESSARY).toPlainString());
        }
    }

    /** Records in the audit the card surcharge a payment just recorded carried, if any. */
    private void recordSurcharge(Payment payment, Currency currency) {
        if (payment.getSurcharge().signum() > 0) {
            audit.record("CARD_SURCHARGE payment=" + payment.getId() + " amount="
                    + payment.getSurcharge().setScale(currency.scale(), RoundingMode.UNNECESSARY).toPlainString());
        }
    }

    /** Records in the audit what a payment just recorded holds on the client's account as credit, if anything. */
    /**
     * Whether the client is on hold: put on hold by hand, its balance less what is in dispute is over its credit limit,
     * or it has a long dispute (see {@link #longDispute}); false if no such client.
     */
    @Transactional(readOnly = true)
    public boolean onHold(Long clientId) {
        return clients.findById(clientId).map(c -> c.isOnHold() || overLimit(c) || longDispute(c.getId())).orElse(false);
    }

    /** How many days a dispute may stay open before its client is put on hold. */
    private static final int LONG_DISPUTE_DAYS = 30;

    /**
     * Whether any of the client's disputes (a whole invoice, some lines or a milestone) has been open for more
     * than 30 days today: the client is then on hold until that dispute is resolved.
     */
    @Transactional(readOnly = true)
    public boolean longDispute(Long clientId) {
        LocalDate limit = today.date().minusDays(LONG_DISPUTE_DAYS);
        return invoices.findByClientIdOrderByIdAsc(clientId).stream()
                .filter(i -> !i.isDraft() && !i.isVoid() && !i.isCreditNote())
                .map(Invoice::oldestOpenDisputeOn)
                .anyMatch(on -> on != null && on.isBefore(limit));
    }

    /** The client's yearly percentage rise of its typed recurring prices; zero when none or no such client. */
    private BigDecimal upliftPct(Long clientId) {
        return clients.findById(clientId).map(Client::getUpliftPct).orElse(BigDecimal.ZERO);
    }

    /** How late a client pays: LOW under 5 days late, MEDIUM under 15, HIGH from 15. */
    public enum RiskBand {
        LOW, MEDIUM, HIGH
    }

    /** A client's payment risk: its score in days to one decimal (null when nothing is paid in full) and its band. */
    public record Risk(BigDecimal score, RiskBand band) {
    }

    /** The weight each next payment's days late count in the payment risk score. */
    private static final BigDecimal RISK_WEIGHT = new BigDecimal("0.5");

    /**
     * The client's payment risk: how many days late each of its invoices paid in full was paid (its paid date,
     * see {@link Invoice#paidOn}, less its due date; 0 when paid by the due date), oldest payment first, averaged
     * with an exponential weight of 0.5 (the first as it is, then 0.5 of each next and 0.5 of the average so far),
     * rounded half up to one decimal. An invoice
     * written off by hand counts as paid in full on the day it was written off. Part-paid invoices, credit notes and invoices cleared without a payment do not count; with none the score is null
     * and the band LOW.
     */
    @Transactional(readOnly = true)
    public Risk riskOf(Long clientId) {
        List<Invoice> list = loadForClient(clientId);
        return riskOf(list, paidOn(list));
    }

    /** The payment risk the invoices give (see {@link #riskOf(Long)}), each paid in full on its date in paidOn. */
    private static Risk riskOf(List<Invoice> invoices, Map<Long, LocalDate> paidOn) {
        List<Invoice> list = invoices.stream().filter(i -> !i.isCreditNote() && i.getDueDate() != null).toList();
        Map<Long, LocalDate> settledOn = new HashMap<>();
        for (Invoice i : list) {
            LocalDate on = i.isWrittenOff() ? i.getWrittenOffOn() : paidOn.get(i.getId());
            if (on != null) {
                settledOn.put(i.getId(), on);
            }
        }
        List<Invoice> paid = list.stream().filter(i -> settledOn.containsKey(i.getId()))
                .sorted(Comparator.comparing((Invoice i) -> settledOn.get(i.getId())).thenComparing(Invoice::getId))
                .toList();
        BigDecimal average = null;
        for (Invoice i : paid) {
            BigDecimal days = BigDecimal.valueOf(Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(i.getDueDate(), settledOn.get(i.getId()))));
            average = average == null ? days
                    : RISK_WEIGHT.multiply(days).add(BigDecimal.ONE.subtract(RISK_WEIGHT).multiply(average));
        }
        if (average == null) {
            return new Risk(null, RiskBand.LOW);
        }
        BigDecimal score = average.setScale(1, RoundingMode.HALF_UP);
        RiskBand band = score.compareTo(BigDecimal.valueOf(5)) < 0 ? RiskBand.LOW
                : score.compareTo(BigDecimal.valueOf(15)) < 0 ? RiskBand.MEDIUM : RiskBand.HIGH;
        return new Risk(score, band);
    }

    /** The payment risk band the client's invoices other than this one give (see {@link #riskOf(Long)}). */
    private RiskBand bandOfOthers(Invoice invoice) {
        List<Invoice> others = loadForClient(invoice.getClientId()).stream()
                .filter(o -> !o.getId().equals(invoice.getId())).toList();
        return riskOf(others, paidOn(others)).band();
    }

    /** Whether the invoice needs approval before it is sent (see {@link Invoice#approval}), as of today. */
    @Transactional(readOnly = true)
    public Invoice.Approval approval(Invoice invoice) {
        RiskBand band = riskOf(invoice.getClientId()).band();
        invoice.halveApprovalLimit(band == RiskBand.MEDIUM);
        boolean highRisk = invoice.isDraft() && !invoice.isCreditNote() && band == RiskBand.HIGH;
        invoice.markHighRisk(highRisk);
        boolean overCreditLimit = invoice.isDraft() && !invoice.isCreditNote() && wouldGoOverLimit(invoice);
        boolean duplicate = !possibleDuplicateOf(invoice).isEmpty();
        return invoice.approval(taxRate.percent(), today.date(), highRisk, overCreditLimit, duplicate);
    }

    /**
     * The invoices the draft is a possible duplicate of, lowest id first: its client's sent invoices (not
     * void, not credit notes, not rebilled or fully credited) issued within the workspace's duplicate window of days before today whose
     * {@link Invoice#fingerprint fingerprint} is the draft's, and its client's earlier drafts (lower id, not credit or
     * adjustment notes) with the same fingerprint. Empty for anything but a draft with lines, or when the workspace
     * has no duplicate window.
     */
    @Transactional(readOnly = true)
    public List<Invoice> possibleDuplicateOf(Invoice invoice) {
        Integer days = duplicateWindow.days();
        String fingerprint = invoice.fingerprint();
        if (days == null || fingerprint == null || !invoice.isDraft() || invoice.isCreditNote()) {
            return List.of();
        }
        List<Invoice> all = adjusted(invoices.findByClientIdOrderByIdAsc(invoice.getClientId()));
        List<Invoice> candidates = new ArrayList<>(duplicateCandidates(invoice, all, days));
        all.stream().filter(i -> i.isDraft() && !i.isCreditNote() && !i.isAdjustment()
                && invoice.getId() != null && i.getId() < invoice.getId()).forEach(candidates::add);
        return candidates.stream().filter(i -> fingerprint.equals(i.fingerprint()))
                .sorted(Comparator.comparing(Invoice::getId)).toList();
    }

    /**
     * Of the client's invoices given, those another of its invoices can be a duplicate of: sent (not void, not credit
     * or adjustment notes, not rebilled or fully credited) and issued within the duplicate window of days before today.
     */
    private List<Invoice> duplicateCandidates(Invoice invoice, List<Invoice> all, int days) {
        LocalDate on = today.date();
        LocalDate from = on.minusDays(days);
        return all.stream()
                .filter(i -> !i.getId().equals(invoice.getId()) && !i.isDraft() && !i.isVoid() && !i.isCreditNote() && !i.isAdjustment())
                // A rebilled or fully credited invoice is cancelled: nothing duplicates it.
                .filter(i -> i.getRebilledOn() == null && i.getCredited().compareTo(i.total(taxRate.percent())) < 0)
                .filter(i -> i.getIssueDate() != null && !i.getIssueDate().isBefore(from) && !i.getIssueDate().isAfter(on))
                .toList();
    }

    /**
     * Whether sending the draft as it stands would take its client's balance over its credit limit (see
     * {@link #creditLimitRefusal}, without repricing its lines); false when the client has no limit.
     */
    private boolean wouldGoOverLimit(Invoice invoice) {
        Client client = clients.findById(invoice.getClientId()).orElse(null);
        if (client == null || client.getCreditLimit() == null) {
            return false;
        }
        BigDecimal after = creditBalance(client.getId()).add(committed(client.getId(), invoice.getId()))
                .add(invoice.owedTotal(taxRate.percent()));
        return after.compareTo(creditLimitOf(client)) > 0;
    }

    /**
     * The credit limit that counts for the client: a HIGH payment risk client's is half of what is set, rounded
     * down to the cent; null when it has no limit.
     */
    @Transactional(readOnly = true)
    public BigDecimal creditLimitOf(Client client) {
        if (client.getCreditLimit() == null) {
            return null;
        }
        return riskOf(client.getId()).band() == RiskBand.HIGH
                ? client.getCreditLimit().divide(BigDecimal.valueOf(2), 2, RoundingMode.DOWN)
                : client.getCreditLimit();
    }

    /**
     * Whether the client's balance less what is in dispute (see {@link #creditBalance}) is over its credit limit;
     * false if no such client or it has no limit.
     */
    @Transactional(readOnly = true)
    public boolean overLimit(Long clientId) {
        return clients.findById(clientId).map(this::overLimit).orElse(false);
    }

    private boolean overLimit(Client client) {
        return client.getCreditLimit() != null && creditBalance(client.getId()).compareTo(creditLimitOf(client)) > 0;
    }

    /**
     * Records a payment from a client on hold, whatever invoice is open or referenced: it pays the client's
     * overdue invoices (not in dispute), oldest due date first (lower id first on the same date), each paid
     * off before the next, and only what is left is held on the client's account as credit. A date after
     * today is refused.
     */
    private Payment payOverdueThenHold(Long clientId, BigDecimal paidAmount, Currency paidIn, LocalDate date,
            PaymentMethod method) {
        Map<Long, BigDecimal> dues = new HashMap<>();
        LocalDate now = today.date();
        List<Invoice> overdue = inPaymentOrder(loadForClient(clientId), dues).stream()
                .filter(i -> dues.get(i.getId()).signum() > 0
                        && i.statusWhenPaid(paid(List.of(i)).get(i.getId()), taxRate.percent(), now) == InvoiceStatus.OVERDUE
                        && (i.getIssueDate() == null || !date.isBefore(i.getIssueDate())))
                .sorted(Comparator.comparing(Invoice::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Invoice::getId))
                .toList();
        if (overdue.isEmpty()) {
            return holdAsCredit(clientId, paidAmount, paidIn, date, method);
        }
        Currency currency = currencyOf(clientId);
        BigDecimal received = converted(paidAmount, paidIn, currency, date);
        BigDecimal surcharge = surchargeOf(received, currency, method, date);
        BigDecimal fee = feeOf(received, paidIn, currency);
        BigDecimal left = received.subtract(surcharge).subtract(fee);
        List<PaymentAllocation> parts = new ArrayList<>();
        for (Invoice invoice : overdue) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal part = left.min(dues.get(invoice.getId()));
            parts.add(new PaymentAllocation(invoice.getId(), part));
            left = left.subtract(part);
        }
        if (parts.isEmpty()) {
            return holdAsCredit(clientId, paidAmount, paidIn, date, method);
        }
        return recordSpread(clientId, received.subtract(surcharge).subtract(fee), paidAmount, paidIn, date, null, parts,
                method, surcharge, fee, Set.of());
    }

    /**
     * Records a payment from a client on hold that has no overdue invoice to pay: it pays no invoice,
     * and the whole of it is held on the client's account as credit, recorded in the audit. A date after
     * today is refused.
     */
    private Payment holdAsCredit(Long clientId, BigDecimal paidAmount, Currency paidIn, LocalDate date,
            PaymentMethod method) {
        LocalDate now = today.date();
        if (date.isAfter(now)) {
            throw new InvoiceRejectedException("The payment date cannot be after today (" + now + ").");
        }
        Currency currency = currencyOf(clientId);
        BigDecimal amount = converted(paidAmount, paidIn, currency, date);
        BigDecimal surcharge = surchargeOf(amount, currency, method, date);
        BigDecimal fee = feeOf(amount, paidIn, currency);
        Payment payment = payments.saveAndFlush(Payment.deposit(clientId, amount, date)
                .paidIn(paidIn, paidAmount, currency).by(method).surcharged(surcharge).feeCharged(fee));
        recordFee(payment, currency);
        recordSurcharge(payment, currency);
        recordCreditHeld(payment);
        return payment;
    }

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
