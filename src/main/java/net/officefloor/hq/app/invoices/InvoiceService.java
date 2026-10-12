package net.officefloor.hq.app.invoices;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.CardSurcharge;
import net.officefloor.hq.app.ExchangeRates;
import net.officefloor.hq.app.FxFee;
import net.officefloor.hq.app.LateInterestRate;
import net.officefloor.hq.app.PriceList;
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

    public InvoiceService(InvoiceRepository invoices, PaymentRepository payments, ClientRepository clients,
            Today today, TaxRate taxRate, Audit audit, EntityManager entityManager, ExchangeRates exchangeRates,
            LateInterestRate lateInterestRate, CardSurcharge cardSurcharge, FxFee fxFee, PriceList priceList,
            RegionTerms regionTerms) {
        this.regionTerms = regionTerms;
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
        List<Payment> found = payments.findForInvoices(list.stream().map(Invoice::getId).toList());
        BigDecimal rate = taxRate.percent();
        BigDecimal annual = lateInterestRate.percent();
        LocalDate now = today.date();
        list.forEach(i -> {
            i.priceAsOf(now);
            if (i.isDraft() && !i.isCreditNote()) {
                repriceWhereRated(i, now);
            }
            i.accrueLateInterest(found, rate, annual, now);
        });
        return list;
    }

    @Transactional(readOnly = true)
    public Optional<Invoice> find(Long id) {
        return load(id);
    }

    /**
     * An empty draft for the client, issued today, due on the client's terms and starting with the
     * client's standard discount and retention; empty if no such client.
     */
    @Transactional
    public Optional<Invoice> newDraft(Long clientId) {
        return clients.findById(clientId).map(client -> {
            LocalDate issued = today.date();
            Invoice draft = Invoice.draft(clientId, client.isTaxExempt(), client.getTaxExemptUntil(), client.isPricesIncludeTax(), taxRate.forRegion(client.getRegion()),
                    taxRate.changesFor(client.getRegion()), taxRate.roundsDown(client.getRegion()),
                    outOfHomeRegion(client) ? client.getTaxNumber() : null, client.getCurrency(), issued, dueDateFor(clientId, issued));
            draft.setDiscountPct(client.getDiscountPct());
            draft.setRetentionPct(client.getRetentionPct());
            return invoices.save(draft);
        });
    }

    /** Whether the client is in a region other than the workspace's home region (when it has one). */
    private boolean outOfHomeRegion(Client client) {
        String home = taxRate.homeRegion();
        return home != null && client.getRegion() != null && !client.getRegion().equals(home);
    }

    /**
     * A new draft for the invoice's client with copies of its lines and the same invoice discount,
     * retention and instalment plan (not the client's standard ones), issued today and due as any new invoice is, and records it in the audit; empty if no such
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
    public Optional<Invoice> addLine(Long invoiceId, String description, BigDecimal qty, String unitType,
            BigDecimal unitPrice, boolean overtime, boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate,
            LocalDate periodStart, LocalDate periodEnd) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            LinePrice price = linePrice(invoice, unitType, unitPrice);
            boolean reopened = reopenForChange(invoice, "A paid invoice cannot take new lines.");
            InvoiceLine line = new InvoiceLine(description, qty, unitType, price.unitPrice(), price.tiers(), price.increment(), price.minUnits(), overtime, taxExempt,
                    discountPct, taxRate);
            line.setCardPriced(price.fromCard());
            line.setPeriod(periodStart, periodEnd);
            invoice.addLine(line);
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
            String unitType, BigDecimal unitPrice, boolean overtime, boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate,
            LocalDate periodStart, LocalDate periodEnd) {
        Optional<Invoice> found = load(invoiceId);
        if (found.isEmpty()) {
            return found;
        }
        Invoice invoice = found.get();
        if (!invoice.hasLine(lineId)) {
            return Optional.empty();
        }
        LinePrice price = linePrice(invoice, unitType, unitPrice);
        boolean reopened = reopenForChange(invoice, "A paid invoice cannot have lines changed.");
        invoice.updateLine(lineId, description, qty, unitType, price.unitPrice(), price.tiers(), price.increment(), price.minUnits(), overtime, taxExempt,
                discountPct, taxRate, periodStart, periodEnd);
        invoice.getLines().stream().filter(l -> lineId.equals(l.getId())).forEach(l -> l.setCardPriced(price.fromCard()));
        invoices.saveAndFlush(invoice);
        if (reopened) {
            audit.record("INVOICE_REOPENED id=" + invoice.getId());
        }
        return found;
    }

    /**
     * Gives an invoice a percentage discount, or a fixed amount off when one is given; the one given
     * clears the other, and the cap (null for none) limits everything taken off, line discounts included. Empty if no such invoice. The values have already been checked; a fixed amount
     * over the subtotal is refused; the reason is optional. A sent invoice with no payments goes back to draft; one with a
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
     * Sets the percentage of a draft's amount before tax held back as retention, and the most it holds
     * back (null for no cap); empty if no such invoice.
     * The percentage has already been checked; any invoice that is not a draft, or a credit note, is refused.
     */
    @Transactional
    public Optional<Invoice> setRetention(Long invoiceId, BigDecimal retentionPct, BigDecimal retentionCap) {
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
            if (retentionCap != null) {
                requireWhole(invoice.currency(), retentionCap, "The retention cap");
            }
            invoice.setRetentionPct(retentionPct);
            invoice.setRetentionCap(retentionCap);
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Spreads a draft over the number of monthly instalments at the annual interest rate, after a down
     * payment of the percentage of the grand total; empty if no
     * such invoice. Both have already been checked; any invoice that is not a draft, a credit note, or
     * one holding back retention is refused, as is a plan whose monthly instalment would be worth less
     * than {@link Invoice#MIN_INSTALMENT_USD} US dollars at today's rates.
     */
    @Transactional
    public Optional<Invoice> setPlan(Long invoiceId, int instalments, BigDecimal instalmentRate,
            BigDecimal planDownPct) {
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
            invoice.setPlan(instalments, instalmentRate, planDownPct);
            if (invoice.isInstalmentTooSmall(taxRate.percent(), today.date())) {
                throw new InvoiceRejectedException("The monthly instalment must be worth at least $"
                        + Invoice.MIN_INSTALMENT_USD.toPlainString() + " at today's exchange rates.");
            }
            invoices.saveAndFlush(invoice);
        });
        return found;
    }

    /**
     * Releases the given amount of the retention held back on a sent invoice today (all that is still
     * held when it is null), adding it to the amount due (all that is released falling due
     * {@link Invoice#RETENTION_DUE_DAYS} days after the latest release), and records the amount
     * released in the audit; empty if no such invoice. Refused while anything other than retention
     * already released is still due, when there is no retention held to release, or when the amount is
     * more than is held.
     */
    @Transactional
    public Optional<Invoice> releaseRetention(Long invoiceId, BigDecimal amount) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (invoice.isDraft() || invoice.isVoid() || invoice.isCreditNote()) {
                throw new InvoiceRejectedException("Retention can only be released on a sent invoice.");
            }
            if (invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("The retention was written off with the invoice.");
            }
            BigDecimal rate = taxRate.percent();
            BigDecimal held = invoice.retentionHeld(rate);
            if (held.signum() <= 0) {
                throw new InvoiceRejectedException("There is no retention held to release.");
            }
            BigDecimal release = amount == null ? held
                    : amount.setScale(invoice.currency().scale(), RoundingMode.HALF_UP);
            if (amount != null) {
                requireWhole(invoice.currency(), amount, "The amount to release");
                if (amount.signum() <= 0) {
                    throw new InvoiceRejectedException("The amount to release must be more than zero.");
                }
                if (amount.compareTo(held) > 0) {
                    throw new InvoiceRejectedException("The amount to release cannot be more than the "
                            + invoice.currency().format(held) + " still held.");
                }
            }
            BigDecimal due = invoice.amountDue(paid(List.of(invoice)).get(invoice.getId()), rate);
            BigDecimal elseDue = due.subtract(due.min(invoice.retentionReleased(rate).add(invoice.getRetentionInterest())).max(BigDecimal.ZERO));
            if (elseDue.signum() > 0) {
                throw new InvoiceRejectedException("The retention can only be released once nothing else is due; "
                        + invoice.currency().format(elseDue) + " is still due.");
            }
            invoice.releaseRetention(today.date(), release);
            invoices.saveAndFlush(invoice);
            audit.record("RETENTION_RELEASED id=" + invoice.getId() + " amount=" + release.toPlainString());
        });
        return found;
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
            if (invoice.hasPlan() || waived.signum() <= 0) {
                throw new InvoiceRejectedException("There is no unpaid late interest to waive.");
            }
            invoices.saveAndFlush(invoice);
            audit.record("INTEREST_WAIVED id=" + invoice.getId() + " amount=" + waived.toPlainString());
        });
        return found;
    }

    /**
     * Skips an instalment of a sent invoice's plan: it pays only its interest, and the instalments after
     * it are worked out again on the balance still owed (see {@link Invoice#schedule}); records it in the
     * audit; empty if no such invoice. An instalment that is overdue, paid (even in part) or the last is
     * refused, as is skipping a second one.
     */
    @Transactional
    public Optional<Invoice> skipInstalment(Long invoiceId, int number) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.hasPlan() || invoice.isDraft() || invoice.isVoid() || invoice.isCreditNote()
                    || invoice.isWrittenOff()) {
                throw new InvoiceRejectedException("Only an instalment of a sent plan can be skipped.");
            }
            BigDecimal rate = taxRate.percent();
            BigDecimal paid = paid(List.of(invoice)).get(invoice.getId());
            List<Instalment.Progress> progress = invoice.instalmentProgress(paid, rate, today.date());
            Instalment.Progress instalment = progress.stream().filter(p -> p.instalment().number() == number)
                    .findFirst().orElseThrow(() -> new InvoiceRejectedException("There is no such instalment to skip."));
            if (number < 1) {
                throw new InvoiceRejectedException("The down payment cannot be skipped.");
            }
            if (instalment == progress.get(progress.size() - 1)) {
                throw new InvoiceRejectedException("The last instalment cannot be skipped.");
            }
            if (instalment.status() == Instalment.Status.OVERDUE) {
                throw new InvoiceRejectedException("An overdue instalment cannot be skipped.");
            }
            if (instalment.paid().signum() > 0) {
                throw new InvoiceRejectedException("An instalment already paid cannot be skipped.");
            }
            if (invoice.getSkippedInstalment() != null) {
                throw new InvoiceRejectedException("This plan has already skipped an instalment.");
            }
            invoice.skipInstalment(number);
            invoices.saveAndFlush(invoice);
            withInterest(List.of(invoice));
            audit.record("INSTALMENT_SKIPPED id=" + invoice.getId() + " instalment=" + number);
        });
        return found;
    }

    /**
     * Writes off a sent invoice that still has something owed as a bad debt today: everything still
     * owed on it, late charges and retention included, and records what was written off in the audit;
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
            if (invoice.amountDue(paid, rate).add(invoice.retentionHeld(rate)).signum() <= 0) {
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
        if (invoice.isWrittenOff()) {
            throw new InvoiceRejectedException("A written-off invoice cannot be changed.");
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
            if (invoice.dueDatePassedBy(today.date())) {
                throw new InvoiceRejectedException("The due date (" + invoice.getDueDate()
                        + ") is already past; set a later due date before sending the invoice.");
            }
            if (invoice.approval(taxRate.percent()) == Invoice.Approval.NEEDS_APPROVAL) {
                throw new InvoiceRejectedException(
                        invoice.discountIsLarge()
                                ? "The discount is more than 20% of the subtotal; approve the invoice before sending it."
                                : "The grand total is over the approval limit; approve the invoice before sending it.");
            }
            String overLimit = creditLimitRefusal(invoice);
            if (overLimit != null) {
                throw new InvoiceRejectedException(overLimit);
            }
            markSent(invoice);
        });
        return found;
    }

    /**
     * Approves a draft whose grand total is over the workspace's approval limit, or whose discount takes
     * off more than a fifth of the subtotal, keeping that total,
     * and records it in the audit; empty if no such invoice. An invoice that needs no approval, or is
     * already approved at its total, is refused.
     */
    @Transactional
    public Optional<Invoice> approve(Long invoiceId) {
        Optional<Invoice> found = load(invoiceId);
        found.ifPresent(invoice -> {
            if (!invoice.isDraft()) {
                throw new InvoiceRejectedException("Only a draft invoice can be approved.");
            }
            BigDecimal rate = taxRate.percent();
            if (invoice.approval(rate) != Invoice.Approval.NEEDS_APPROVAL) {
                throw new InvoiceRejectedException(invoice.approval(rate) == Invoice.Approval.APPROVED
                        ? "This invoice is already approved."
                        : "This invoice is within the approval limit and its discount is not over 20%, so it needs no approval.");
            }
            BigDecimal total = invoice.total(rate);
            invoice.approve(total);
            invoices.saveAndFlush(invoice);
            audit.record("INVOICE_APPROVED id=" + invoice.getId() + " total=" + total.toPlainString());
        });
        return found;
    }

    /**
     * Sends each of the client's drafts that has at least one line, lowest id first, as {@link #send}
     * would; drafts with no lines, whose due date set by
     * hand is already past, that need approval, or that would take the client over its credit limit
     * (judged on its balance after the drafts sent before them), stay drafts.
     */
    @Transactional
    public void sendAllDrafts(Long clientId) {
        if (onHold(clientId)) {
            return;
        }
        loadForClient(clientId).stream()
                .filter(invoice -> invoice.isDraft() && !invoice.getLines().isEmpty()
                        && !invoice.dueDatePassedBy(today.date())
                        && invoice.approval(taxRate.percent()) != Invoice.Approval.NEEDS_APPROVAL)
                .forEach(invoice -> {
                    if (!invoice.isCreditNote()) {
                        // Read again the client's sent invoices: one sent just before counts towards its month's volume.
                        entityManager.refresh(invoice);
                        if (creditLimitRefusal(invoice) == null) {
                            markSent(invoice);
                        }
                    } else if (creditNoteRefusal(invoice) == null) {
                        issue(invoice);
                    }
                });
    }

    /**
     * What the client owes: what is still due on its sent invoices (late interest included, withholding
     * left out) plus the retention held on them, less its unused credit. Void invoices and credit notes
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
            owed = owed.add(i.amountDue(paid.get(i.getId()), rate)).add(i.retentionHeld(rate));
        }
        return owed.subtract(credits().getOrDefault(clientId, BigDecimal.ZERO)).setScale(2);
    }

    /**
     * Why sending the draft would take its client over its credit limit, or null when it would not or the
     * client has no limit: the client's balance plus what the invoice adds to it (its grand total, priced
     * as it would be sent, less the withholding) must not be over the limit.
     */
    private String creditLimitRefusal(Invoice invoice) {
        Client client = clients.findById(invoice.getClientId()).orElse(null);
        if (client == null || client.getCreditLimit() == null) {
            return null;
        }
        repriceFromRates(invoice, null, today.date());
        BigDecimal after = balance(client.getId()).add(invoice.owedTotal(taxRate.percent()));
        if (after.compareTo(client.getCreditLimit()) <= 0) {
            return null;
        }
        Currency currency = client.getCurrency();
        return "Sending this invoice would take " + client.getName() + "'s balance to " + currency.format(after)
                + ", over its " + currency.format(client.getCreditLimit()) + " credit limit.";
    }

    /**
     * Why the draft credit note cannot be issued, or null when it can: its original must be a sent
     * invoice (not void). On a plan it comes off the instalments to come, so the plan must have no
     * overdue instalment and the credit note cannot be more than the principal still owed before them.
     */
    private String creditNoteRefusal(Invoice note) {
        Invoice original = load(note.getCreditOf()).orElse(null);
        if (original == null || original.isDraft() || original.isVoid()) {
            return "The invoice this credits is not a sent invoice, so the credit note cannot be issued.";
        }
        String overCredit = overCreditRefusal(note, original);
        if (overCredit != null) {
            return overCredit;
        }
        if (original.hasPlan()) {
            BigDecimal rate = taxRate.percent();
            LocalDate on = today.date();
            withInterest(List.of(original));
            BigDecimal paid = paid(List.of(original)).get(original.getId());
            if (original.instalmentProgress(paid, rate, on).stream()
                    .anyMatch(p -> p.status() == Instalment.Status.OVERDUE)) {
                return "The plan of the invoice this credits has an overdue instalment, so the credit note cannot be issued.";
            }
            BigDecimal principal = original.principalToCome(on, rate);
            if (note.total(rate).compareTo(principal) > 0) {
                return "The credit note cannot be more than the " + original.currency().format(principal)
                        + " principal still owed on the plan's instalments to come.";
            }
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
        repriceFromRates(invoice, null, issued);
        invoice.markSent(issued, dueDateFor(invoice.getClientId(), issued));
        invoices.saveAndFlush(invoice);
        audit.record("INVOICE_SENT id=" + invoice.getId() + " number=" + invoice.number()
                + " total=" + invoice.total(taxRate.percent()).toPlainString());
        invoice.taxByRate(taxRate.percent()).forEach((rate, amounts) -> audit.record("TAX_CHARGED id="
                + invoice.getId() + " rate=" + rate.stripTrailingZeros().toPlainString() + " net="
                + amounts[0].toPlainString() + " tax=" + amounts[1].toPlainString()));
        if (invoice.isReverseCharge()) {
            audit.record("REVERSE_CHARGE id=" + invoice.getId() + " number=" + invoice.number()
                    + " total=" + invoice.total(taxRate.percent()).toPlainString());
        }
        BigDecimal withheld = invoice.withholding(taxRate.percent());
        if (withheld.signum() > 0) {
            audit.record("WITHHOLDING id=" + invoice.getId() + " amount=" + withheld.toPlainString());
        }
        if (!invoice.hasPlan()) {
            applyCredit(invoice);
        }
    }

    /**
     * Uses the client's credit on the invoice just sent, up to what it has due: deposits held for it
     * first, then the oldest payment's credit (lower id first on the same date), never a deposit held
     * for another of the client's drafts; each part applied today and recorded in the audit. Credit
     * used counts as paying early: while the early window is open only what settles it early is taken,
     * and when the credit covers that the invoice is settled with the early discount, as an early payment is.
     */
    private void applyCredit(Invoice invoice) {
        BigDecimal paidSoFar = paid(List.of(invoice)).get(invoice.getId());
        BigDecimal due = invoice.amountDue(paidSoFar, taxRate.percent());
        LocalDate now = today.date();
        boolean used = false;
        Map<String, Long> drafts = draftsByReference(invoice.getClientId());
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
        Payment payment = payments.saveAndFlush(Payment.deposit(clientId, amount, date, reference)
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
        Map<String, Long> drafts = draftsByReference(clientId);
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
     */
    @Transactional
    public Optional<Invoice> recordPayment(Long invoiceId, BigDecimal paidAmount, Currency paidIn, LocalDate date,
            String reference, PaymentMethod method) {
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
                holdAsCredit(invoice.getClientId(), paidAmount, paidIn, date, method);
                return;
            }
            BigDecimal received = converted(paidAmount, paidIn, invoice.currency(), date);
            // Only what is left once the conversion fee and card surcharge are taken out pays the invoice.
            BigDecimal surcharge = surchargeOf(received, invoice.currency(), method, date);
            BigDecimal fee = feeOf(received, paidIn, invoice.currency());
            BigDecimal amount = received.subtract(surcharge).subtract(fee);
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
            BigDecimal saved = BigDecimal.ZERO;
            BigDecimal payoff = date.isAfter(now) ? null : invoice.payoff(paidSoFar, taxRate.percent(), now);
            if (payoff != null && amount.compareTo(payoff) >= 0) {
                saved = due.subtract(payoff).max(BigDecimal.ZERO);
                invoice.saveInterest(saved);
                due = payoff;
            }
            BigDecimal rounding = BigDecimal.ZERO;
            if (cash && amount.compareTo(invoice.currency().toCash(due)) == 0) {
                rounding = amount.subtract(due);
            }
            BigDecimal applied = amount.min(due.add(rounding));
            if (invoice.hasPlan() && invoice.isTooSmallPart(applied, paidSoFar, taxRate.percent())) {
                throw new InvoiceRejectedException("A payment must be at least the "
                        + invoice.currency().format(invoice.earliestUnpaid(paidSoFar, taxRate.percent()))
                        + " still unpaid on the earliest unpaid instalment, unless it settles the plan.");
            }
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
            Payment payment = payments.saveAndFlush(Payment.against(invoice, received, applied, date, reference)
                    .paidIn(paidIn, paidAmount, invoice.currency()).by(method).surcharged(surcharge).feeCharged(fee));
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
            if (payoff != null && amount.compareTo(payoff) >= 0) {
                invoices.saveAndFlush(invoice);
                audit.record("PLAN_SETTLED id=" + invoice.getId() + " saved=" + saved.toPlainString());
            }
            recordWriteOff(invoice);
            recordCreditHeld(payment);
        });
        return found;
    }

    /**
     * Records one payment of the amount on the date against the client's unpaid sent invoices: the overdue
     * ones first, earliest due date first (lower id first on the same due date), then the rest from the
     * largest amount due to the smallest (lower id first on the same amount), paying each off in full
     * before the next.
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
     */
    @Transactional
    public Payment recordAutoPayment(Long clientId, BigDecimal paidAmount, Currency paidIn, LocalDate date,
            List<QuotedReference> references, PaymentMethod method) {
        if (onHold(clientId)) {
            return holdAsCredit(clientId, paidAmount, paidIn, date, method);
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
        if (!references.isEmpty()) {
            Set<Long> named = new HashSet<>();
            Set<Long> stated = new HashSet<>();
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
            candidates = ofClient.stream().filter(i -> named.contains(i.getId())).toList();
        }
        Map<Long, BigDecimal> dues = new HashMap<>();
        List<Invoice> sent = inPaymentOrder(candidates, dues);
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
        if (parts.isEmpty()) {
            throw new InvoiceRejectedException(references.isEmpty() ? "The client has no unpaid invoices to pay."
                    : "Those invoices have already been paid.");
        }
        String reference = references.isEmpty() ? null : PaymentReference.of(parts.get(0).getInvoiceId());
        return recordSpread(clientId, amount, paidAmount, paidIn, date, reference, parts, method, surcharge, fee);
    }

    /**
     * The sent invoices among these (not drafts, void invoices or credit notes) in the payment order:
     * the overdue ones first, earliest due date first, then the rest from the largest amount due to the
     * smallest; lower id first on a tie. Fills in what each has due.
     */
    private List<Invoice> inPaymentOrder(List<Invoice> candidates, Map<Long, BigDecimal> dues) {
        List<Invoice> unordered = candidates.stream()
                .filter(i -> !i.isDraft() && !i.isVoid() && !i.isCreditNote())
                .toList();
        Map<Long, BigDecimal> paid = paid(unordered);
        LocalDate now = today.date();
        Set<Long> overdue = new HashSet<>();
        for (Invoice invoice : unordered) {
            BigDecimal invoicePaid = paid.get(invoice.getId());
            dues.put(invoice.getId(), invoice.amountDue(invoicePaid, taxRate.percent()));
            if (invoice.statusWhenPaid(invoicePaid, taxRate.percent(), now) == InvoiceStatus.OVERDUE) {
                overdue.add(invoice.getId());
            }
        }
        // Overdue first, oldest due date first; then the rest, largest amount due first; lower id on a tie.
        Comparator<Invoice> byPriority = Comparator.<Invoice, Boolean>comparing(i -> !overdue.contains(i.getId()))
                .thenComparing(i -> overdue.contains(i.getId()) ? i.getDueDate() : null,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(i -> overdue.contains(i.getId()) ? BigDecimal.ZERO : dues.get(i.getId()),
                        Comparator.reverseOrder())
                .thenComparing(Invoice::getId);
        return unordered.stream().sorted(byPriority).toList();
    }

    /**
     * Uses the client's unused credit now on what they owe: the oldest payment's credit first (lower id
     * first on the same date), never a deposit held for one of the client's drafts, spread over their
     * sent invoices in the payment order (see {@link #inPaymentOrder}), each paid off before the next;
     * each part applied today and recorded in the audit. Empty if no such client. Refused when the
     * client has no credit to use or owes nothing.
     */
    @Transactional
    public Optional<Long> applyCreditNow(Long clientId) {
        Optional<Client> client = clients.findById(clientId);
        if (client.isEmpty()) {
            return Optional.empty();
        }
        if (client.get().isOnHold()) {
            throw new InvoiceRejectedException("The client is on hold: lift the hold before applying their credit.");
        }
        Map<String, Long> drafts = draftsByReference(clientId);
        List<Payment> usable = payments.findForClient(clientId).stream()
                .filter(p -> heldFor(p, drafts) == null && p.credit().signum() > 0)
                .toList();
        if (usable.isEmpty()) {
            throw new InvoiceRejectedException("The client has no unused credit to apply.");
        }
        Map<Long, BigDecimal> dues = new HashMap<>();
        List<Invoice> owing = inPaymentOrder(loadForClient(clientId), dues).stream()
                .filter(i -> dues.get(i.getId()).signum() > 0)
                .toList();
        if (owing.isEmpty()) {
            throw new InvoiceRejectedException("The client owes nothing to apply credit to.");
        }
        LocalDate now = today.date();
        Set<Invoice> reached = new LinkedHashSet<>();
        Iterator<Payment> from = usable.iterator();
        Payment payment = from.next();
        BigDecimal credit = payment.credit();
        for (Invoice invoice : owing) {
            BigDecimal due = dues.get(invoice.getId());
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
     * held on the client's account as credit and recorded in the audit. Each must go to a different
     * sent invoice of the client and be no more than what it still has due. The date must be no later
     * than today nor before any of the invoices' issue dates. The reference (null when none) has
     * already been checked; it must be the reference of one of the invoices.
     */
    private Payment recordSpread(Long clientId, BigDecimal amount, BigDecimal paidAmount, Currency paidIn,
            LocalDate date, String reference, List<PaymentAllocation> parts, PaymentMethod method,
            BigDecimal surcharge, BigDecimal fee) {
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
     * The draft the payment is held for, or null when it is ordinary credit: a payment whose reference
     * (an RF reference or invoice number, as stored) is that of one of the client's drafts is a deposit
     * held for it. Once that invoice has been sent the deposit's rest is ordinary credit.
     */
    private static Long heldFor(Payment payment, Map<String, Long> drafts) {
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
    /** Whether the client is on hold; false if no such client. */
    private boolean onHold(Long clientId) {
        return clients.findById(clientId).map(Client::isOnHold).orElse(false);
    }

    /**
     * Records a payment from a client on hold: it pays no invoice, whatever invoice is open or referenced,
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
            // An invoice the payment settled early is no longer paid off, so its early discount or the
            // interest its plan's payoff dropped lapses.
            payment.getAllocations().forEach(a -> load(a.getInvoiceId()).ifPresent(invoice -> {
                if (invoice.getEarlyDiscountTaken().signum() > 0 || invoice.getInterestSaved().signum() > 0) {
                    invoice.takeEarlyDiscount(BigDecimal.ZERO);
                    invoice.saveInterest(BigDecimal.ZERO);
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
