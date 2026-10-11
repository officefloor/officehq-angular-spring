package net.officefloor.hq.app.invoices;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.Formula;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** An invoice raised for a client, made of lines. */
@Entity
@Table(name = "invoice")
public class Invoice {

    /** A due date set by hand can be at most this many days after the issue date. */
    public static final int MAX_DUE_DAYS = 120;

    /** Payments that leave less than this still due have the rest written off. */
    public static final BigDecimal WRITE_OFF_LIMIT = new BigDecimal("1.00");

    /** A part payment must be at least this share of the grand total, unless it settles the invoice. */
    public static final BigDecimal MINIMUM_PART_SHARE = new BigDecimal("0.10");

    /** The most retention a draft can hold back, as a percentage of the grand total. */
    public static final BigDecimal MAX_RETENTION_PCT = new BigDecimal("20");

    /** Released retention falls due this many days after the day it was released. */
    public static final int RETENTION_DUE_DAYS = 30;

    /** The fewest and most monthly instalments a plan can have. */
    public static final int MIN_INSTALMENTS = 2;
    public static final int MAX_INSTALMENTS = 24;

    /** The highest annual interest rate a plan can charge, as a percentage. */
    public static final BigDecimal MAX_INSTALMENT_RATE = new BigDecimal("30");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id")
    private Long clientId;

    @Enumerated(EnumType.STRING)
    private InvoiceStatus status;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "manual_due_date")
    private boolean manualDueDate;

    @Column(name = "discount_pct")
    private BigDecimal discountPct = BigDecimal.ZERO;

    @Column(name = "discount_amount")
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "discount_cap")
    private BigDecimal discountCap;

    @Column(name = "discount_reason")
    private String discountReason;

    @Column(name = "retention_pct")
    private BigDecimal retentionPct = BigDecimal.ZERO;

    @Column(name = "retention_released_on")
    private LocalDate retentionReleasedOn;

    /** How many monthly instalments the invoice is spread over; null when it has no plan. */
    @Column(name = "instalments")
    private Integer instalments;

    /** The annual interest rate of the plan, a percentage from 0 to {@link #MAX_INSTALMENT_RATE}. */
    @Column(name = "instalment_rate")
    private BigDecimal instalmentRate = BigDecimal.ZERO;

    /** Whether the invoice's client is tax exempt, so no line is taxed; read from the client. */
    @Formula("(SELECT c.tax_exempt FROM client c WHERE c.id = client_id)")
    private boolean clientTaxExempt;

    /** The id of the invoice this credit note credits; null for an ordinary invoice. */
    @Column(name = "credit_of")
    private Long creditOf;

    /** An issued credit note's grand total, kept when it is issued; null until then. */
    @Column(name = "credit_total")
    private BigDecimal creditTotal;

    /**
     * What this credit note took off its original when issued (what it owed, then retention it held);
     * null until then. Any rest of its grand total was held on the client's account as credit.
     */
    @Column(name = "credit_applied")
    private BigDecimal creditApplied;

    /** The part of {@link #creditApplied} that cleared retention its original held back. */
    @Column(name = "credit_retention")
    private BigDecimal creditRetention = BigDecimal.ZERO;

    /** What the credit notes issued against this invoice took off it; read from them. */
    @Formula("(SELECT COALESCE(SUM(cn.credit_applied), 0) FROM invoice cn WHERE cn.credit_of = id AND cn.status = 'CREDIT_NOTE')")
    private BigDecimal credited = BigDecimal.ZERO;

    /** The part of what is credited that cleared retention held back; read from the credit notes. */
    @Formula("(SELECT COALESCE(SUM(cn.credit_retention), 0) FROM invoice cn WHERE cn.credit_of = id AND cn.status = 'CREDIT_NOTE')")
    private BigDecimal retentionCredited = BigDecimal.ZERO;

    /** How many credit notes have been issued against this invoice; read from them. */
    @Formula("(SELECT COUNT(*) FROM invoice cn WHERE cn.credit_of = id AND cn.status = 'CREDIT_NOTE')")
    private int creditNotesIssued;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "invoice_id", nullable = false)
    @OrderBy("id")
    private List<InvoiceLine> lines = new ArrayList<>();

    protected Invoice() {
    }

    /** A new, empty draft for a client, issued and due on the given dates. */
    public static Invoice draft(Long clientId, boolean clientTaxExempt, LocalDate issueDate, LocalDate dueDate) {
        Invoice invoice = new Invoice();
        invoice.clientId = clientId;
        invoice.clientTaxExempt = clientTaxExempt;
        invoice.status = InvoiceStatus.DRAFT;
        invoice.issueDate = issueDate;
        invoice.dueDate = dueDate;
        return invoice;
    }

    /**
     * A new draft for the same client with copies of this invoice's lines and the same invoice
     * discount, issued and due on the given dates. Payments stay with this invoice.
     */
    public Invoice duplicate(LocalDate issueDate, LocalDate dueDate) {
        Invoice copy = draft(clientId, clientTaxExempt, issueDate, dueDate);
        copy.discountPct = discountPct;
        copy.discountAmount = discountAmount;
        copy.discountCap = discountCap;
        copy.discountReason = discountReason;
        copy.retentionPct = retentionPct;
        copy.instalments = instalments;
        copy.instalmentRate = instalmentRate;
        lines.forEach(line -> copy.lines.add(line.copy()));
        return copy;
    }

    /**
     * A new, empty draft credit note for the sent invoice, issued on the given date: same client, no
     * discount, taxed as the original's lines are.
     */
    public Invoice creditNote(LocalDate issueDate) {
        Invoice note = draft(clientId, clientTaxExempt, issueDate, null);
        note.creditOf = id;
        return note;
    }

    /** Whether this is a credit note against another invoice. */
    public boolean isCreditNote() {
        return creditOf != null;
    }

    /** The id of the invoice this credit note credits; null for an ordinary invoice. */
    public Long getCreditOf() {
        return creditOf;
    }

    /** The number shown: CN-... for a credit note, otherwise INV-... (see {@link InvoiceNumber}). */
    public String number() {
        return isCreditNote() ? InvoiceNumber.creditNoteOf(id) : InvoiceNumber.of(id);
    }

    /** What the credit notes issued against this invoice add up to, to the cent. */
    public BigDecimal getCredited() {
        return (credited == null ? BigDecimal.ZERO : credited).setScale(2, RoundingMode.HALF_UP);
    }

    /** Whether a credit note has been issued against this invoice: it can then no longer change. */
    public boolean hasCreditNotesIssued() {
        return creditNotesIssued > 0;
    }

    /**
     * Issues a draft credit note on the given date, keeping its grand total at the tax rate and what
     * its original took of it (the part of that clearing held retention).
     */
    public void markIssued(LocalDate issueDate, BigDecimal taxRate, BigDecimal applied, BigDecimal toRetention) {
        this.creditTotal = total(taxRate);
        this.creditApplied = applied;
        this.creditRetention = toRetention;
        this.status = InvoiceStatus.CREDIT_NOTE;
        this.issueDate = issueDate;
    }

    public Long getId() {
        return id;
    }

    public Long getClientId() {
        return clientId;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    /** The percentage taken off the subtotal, 0 to 100 with up to two decimals. */
    public BigDecimal getDiscountPct() {
        return discountPct;
    }

    /** The fixed amount taken off the subtotal, to the cent; 0 when the discount is a percentage. */
    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    /** The most taken off the invoice in all, line discounts included, to the cent; null when there is no cap. */
    public BigDecimal getDiscountCap() {
        return discountCap;
    }

    /** Why the discount was given; null when no reason was given. */
    public String getDiscountReason() {
        return discountReason;
    }

    /** Sets why the discount was given; blank is no reason. */
    public void setDiscountReason(String discountReason) {
        this.discountReason = discountReason == null || discountReason.isBlank() ? null : discountReason.strip();
    }

    /** Whether the discount takes off more than a fifth of the subtotal, after any cap, and so needs a reason. */
    public boolean discountNeedsReason() {
        return discount().multiply(BigDecimal.valueOf(5)).compareTo(subtotal()) > 0;
    }

    /** Whether the discount needs a reason but has none. */
    public boolean discountLacksReason() {
        return discountNeedsReason() && discountReason == null;
    }

    /** Sets the percentage taken off the subtotal, clearing any fixed amount; it has already been checked. */
    public void setDiscountPct(BigDecimal discountPct) {
        this.discountPct = discountPct;
        this.discountAmount = BigDecimal.ZERO;
    }

    /** Sets the most taken off the invoice in all, or null for no cap; it has already been checked as money. */
    public void setDiscountCap(BigDecimal discountCap) {
        this.discountCap = discountCap;
    }

    /**
     * Sets a fixed amount taken off the subtotal, clearing any percentage; it has already been checked
     * as money. An amount over the subtotal is refused.
     */
    public void setDiscountAmount(BigDecimal discountAmount) {
        if (discountAmount.compareTo(subtotal()) > 0) {
            throw new InvoiceRejectedException("The discount cannot be more than the subtotal of "
                    + subtotal().toPlainString() + ".");
        }
        this.discountAmount = discountAmount;
        this.discountPct = BigDecimal.ZERO;
    }

    /** The percentage of the grand total held back as retention, 0 to {@link #MAX_RETENTION_PCT}. */
    public BigDecimal getRetentionPct() {
        return retentionPct;
    }

    /** Sets the percentage held back as retention; it has already been checked. */
    public void setRetentionPct(BigDecimal retentionPct) {
        this.retentionPct = retentionPct;
    }

    /** The day the retention was released; null while it is still held. */
    public LocalDate getRetentionReleasedOn() {
        return retentionReleasedOn;
    }

    /** The day released retention falls due, {@link #RETENTION_DUE_DAYS} after its release; null while held. */
    public LocalDate retentionDueDate() {
        return retentionReleasedOn == null ? null : retentionReleasedOn.plusDays(RETENTION_DUE_DAYS);
    }

    /** Releases the retention on the given day, so it is added to the amount due. */
    public void releaseRetention(LocalDate on) {
        this.retentionReleasedOn = on;
    }

    /** The retention at the tax rate: the retention percentage of the grand total, rounded down to the cent. */
    public BigDecimal retention(BigDecimal taxRate) {
        if (isCreditNote()) {
            return BigDecimal.ZERO.setScale(2);
        }
        return total(taxRate).multiply(retentionPct).divide(BigDecimal.valueOf(100)).setScale(2, RoundingMode.DOWN);
    }

    /**
     * The retention still held back at the tax rate: all of it until it is released, less what credit
     * notes have cleared of it; then nothing.
     */
    public BigDecimal retentionHeld(BigDecimal taxRate) {
        if (retentionReleasedOn != null) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal cleared = retentionCredited == null ? BigDecimal.ZERO : retentionCredited;
        return retention(taxRate).subtract(cleared).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * How a credit note of the amount comes off this invoice once the given amount has been paid, at
     * the tax rate: first what it still owes, then the retention it holds back. The part taken off in
     * all, and of that the part clearing retention; the rest is left over as credit.
     */
    public BigDecimal[] creditTaken(BigDecimal amount, BigDecimal paid, BigDecimal taxRate) {
        BigDecimal toDue = amount.min(amountDue(paid, taxRate).max(BigDecimal.ZERO));
        BigDecimal toRetention = amount.subtract(toDue).min(retentionHeld(taxRate));
        return new BigDecimal[] { toDue.add(toRetention), toRetention };
    }

    /** The retention released at the tax rate: nothing until it is released, then all of it. */
    public BigDecimal retentionReleased(BigDecimal taxRate) {
        return retentionReleasedOn == null ? BigDecimal.ZERO.setScale(2) : retention(taxRate);
    }

    /**
     * What the invoice is owed in all, held retention left out: the grand total (the plan total when it
     * is spread over instalments) less what is credited and held.
     */
    private BigDecimal payable(BigDecimal taxRate) {
        BigDecimal owed = hasPlan() ? planTotal(taxRate) : total(taxRate);
        return owed.subtract(getCredited()).subtract(retentionHeld(taxRate));
    }

    /** Whether the invoice is spread over monthly instalments. */
    public boolean hasPlan() {
        return instalments != null;
    }

    /** How many monthly instalments the invoice is spread over; null when it has no plan. */
    public Integer getInstalments() {
        return instalments;
    }

    /** The plan's annual interest rate, a percentage; 0 when it has no plan. */
    public BigDecimal getInstalmentRate() {
        return instalmentRate;
    }

    /** Spreads the invoice over the number of monthly instalments at the annual rate; both have already been checked. */
    public void setPlan(int instalments, BigDecimal instalmentRate) {
        this.instalments = instalments;
        this.instalmentRate = instalmentRate;
    }

    /**
     * The plan's schedule at the tax rate, an ordinary loan of the grand total; empty when there is no
     * plan. With the monthly rate i (the annual rate / 12 / 100) the instalment is P x i / (1 - (1 +
     * i)^-n), or P / n at 0%, rounded half up to the cent. Each month's interest is the balance still
     * owed x i, rounded half up to the cent, and its principal the instalment less the interest; the
     * last instalment pays off the whole balance left plus its interest, so the schedule ends at zero.
     * Instalment k falls due k months after the issue date, on the same day (the month's last day when
     * it is shorter).
     */
    public List<Instalment> schedule(BigDecimal taxRate) {
        if (!hasPlan()) {
            return List.of();
        }
        MathContext mc = MathContext.DECIMAL128;
        BigDecimal principal = total(taxRate);
        BigDecimal i = instalmentRate.divide(BigDecimal.valueOf(1200), mc);
        int n = instalments;
        BigDecimal payment;
        if (i.signum() == 0) {
            payment = principal.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        } else {
            BigDecimal growth = BigDecimal.ONE.add(i).pow(n, mc);
            payment = principal.multiply(i).multiply(growth)
                    .divide(growth.subtract(BigDecimal.ONE), mc).setScale(2, RoundingMode.HALF_UP);
        }
        List<Instalment> schedule = new ArrayList<>(n);
        BigDecimal balance = principal.setScale(2, RoundingMode.HALF_UP);
        for (int k = 1; k <= n; k++) {
            BigDecimal interest = balance.multiply(i).setScale(2, RoundingMode.HALF_UP);
            BigDecimal paid = k == n ? balance.add(interest) : payment;
            BigDecimal repaid = paid.subtract(interest);
            balance = balance.subtract(repaid);
            LocalDate due = issueDate == null ? null : issueDate.plusMonths(k);
            schedule.add(new Instalment(k, due, paid, interest, repaid, balance));
        }
        return schedule;
    }

    /** The plan total at the tax rate: the sum of the instalments; null when there is no plan. */
    public BigDecimal planTotal(BigDecimal taxRate) {
        if (!hasPlan()) {
            return null;
        }
        return schedule(taxRate).stream().map(Instalment::payment).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    public List<InvoiceLine> getLines() {
        return lines;
    }

    /** Whether lines can still be added: only a draft is still being written. */
    public boolean isDraft() {
        return status == InvoiceStatus.DRAFT;
    }

    /** Whether the invoice has been voided: it no longer counts, takes no payments and cannot be changed. */
    public boolean isVoid() {
        return status == InvoiceStatus.VOID;
    }

    /** Voids a sent invoice. */
    public void markVoid() {
        this.status = InvoiceStatus.VOID;
    }

    /** Whether the due date was set by hand; it is then kept when the invoice is sent. */
    public boolean isManualDueDate() {
        return manualDueDate;
    }

    /**
     * Sets the due date by hand: not before the issue date nor more than {@link #MAX_DUE_DAYS} days
     * after it, or it is refused.
     */
    public void setDueDate(LocalDate dueDate) {
        if (issueDate != null && dueDate.isBefore(issueDate)) {
            throw new InvoiceRejectedException(
                    "The due date cannot be before the issue date (" + issueDate + ").");
        }
        if (issueDate != null && dueDate.isAfter(issueDate.plusDays(MAX_DUE_DAYS))) {
            throw new InvoiceRejectedException("The due date can be at most " + MAX_DUE_DAYS
                    + " days after the issue date, so no later than " + issueDate.plusDays(MAX_DUE_DAYS) + ".");
        }
        this.dueDate = dueDate;
        this.manualDueDate = true;
    }

    /**
     * Marks the invoice sent, issued on the given date and due on the given date, unless its due date
     * was set by hand, which is kept.
     */
    public void markSent(LocalDate issueDate, LocalDate dueDate) {
        this.status = InvoiceStatus.SENT;
        this.issueDate = issueDate;
        if (!manualDueDate) {
            this.dueDate = dueDate;
        }
    }

    /** Whether a due date set by hand is already past on the given send date. */
    public boolean dueDatePassedBy(LocalDate sendDate) {
        return manualDueDate && dueDate != null && dueDate.isBefore(sendDate);
    }

    /** Puts a sent invoice back to draft, so it has to be sent again. */
    public void reopen() {
        this.status = InvoiceStatus.DRAFT;
    }

    /** Days past its due date an invoice is given before it becomes overdue: a week's grace. */
    public static final int GRACE_DAYS = 7;

    /**
     * The status on the given day once the given amount has been paid against the grand total at the
     * tax rate: a sent invoice with something still due more than {@link #GRACE_DAYS} days after its
     * due date is overdue, part paid or not. Held retention is never due, so never makes it overdue;
     * released retention is due {@link #RETENTION_DUE_DAYS} days after its release, and payments go to
     * the rest of the invoice before it.
     */
    public InvoiceStatus statusWhenPaid(BigDecimal paid, BigDecimal taxRate, LocalDate today) {
        return statusWhenPaid(paid, null, taxRate, today);
    }

    /**
     * As {@link #statusWhenPaid(BigDecimal, BigDecimal, LocalDate)}, but a fully paid invoice whose
     * paid date (null when not known) is after its due date is paid late.
     */
    public InvoiceStatus statusWhenPaid(BigDecimal paid, LocalDate paidOn, BigDecimal taxRate, LocalDate today) {
        if (status != InvoiceStatus.SENT) {
            return status;
        }
        BigDecimal amountDue = amountDue(paid, taxRate);
        boolean due = amountDue.signum() > 0;
        BigDecimal retentionDue = amountDue.min(retentionReleased(taxRate)).max(BigDecimal.ZERO);
        boolean restDue = amountDue.subtract(retentionDue).signum() > 0;
        if (restDue && dueDate != null && today.isAfter(dueDate.plusDays(GRACE_DAYS))) {
            return InvoiceStatus.OVERDUE;
        }
        if (retentionDue.signum() > 0 && today.isAfter(retentionDueDate().plusDays(GRACE_DAYS))) {
            return InvoiceStatus.OVERDUE;
        }
        if (!due && retentionHeld(taxRate).signum() > 0) {
            return InvoiceStatus.RETENTION_HELD;
        }
        if (paid.signum() <= 0) {
            return !due && getCredited().signum() > 0 ? InvoiceStatus.CREDITED : status;
        }
        if (due) {
            return InvoiceStatus.PART_PAID;
        }
        return paidOn != null && dueDate != null && paidOn.isAfter(dueDate) ? InvoiceStatus.PAID_LATE
                : InvoiceStatus.PAID;
    }

    /**
     * The date the invoice was fully paid: the date of the payment that cleared its grand total at the
     * tax rate, taking the parts of payments (not reversed) in the order they were applied (credit used
     * counts on the day it was applied); null while something is still due or retention is held.
     */
    public LocalDate paidOn(List<Payment> payments, BigDecimal taxRate) {
        BigDecimal total = payable(taxRate);
        if (status != InvoiceStatus.SENT || total.signum() <= 0 || retentionHeld(taxRate).signum() > 0) {
            return null;
        }
        record Part(LocalDate on, Long paymentId, BigDecimal amount) {
        }
        List<Part> inOrder = payments.stream().filter(p -> !p.isReversed())
                .flatMap(p -> p.getAllocations().stream().filter(a -> a.getInvoiceId().equals(id))
                        .map(a -> new Part(a.getDate() == null ? p.getDate() : a.getDate(), p.getId(), a.getAmount())))
                .sorted(Comparator.comparing(Part::on).thenComparing(Part::paymentId)).toList();
        BigDecimal sum = BigDecimal.ZERO;
        for (Part p : inOrder) {
            sum = sum.add(p.amount());
            if (sum.signum() > 0 && total.subtract(sum).compareTo(WRITE_OFF_LIMIT) < 0) {
                return p.on();
            }
        }
        return null;
    }

    /**
     * What is still due once the given amount has been paid: the grand total less it, less what credit
     * notes have credited, less the retention still held, less anything written off — so nothing once
     * payments leave less than {@link #WRITE_OFF_LIMIT}. A credit note itself has nothing due.
     */
    public BigDecimal amountDue(BigDecimal paid, BigDecimal taxRate) {
        if (isCreditNote()) {
            return BigDecimal.ZERO.setScale(2);
        }
        return payable(taxRate).subtract(paid).subtract(writtenOff(paid, taxRate));
    }

    /**
     * What is written off once the given amount has been paid: when payments leave less than
     * {@link #WRITE_OFF_LIMIT} still due, all of what is left; otherwise nothing.
     */
    public BigDecimal writtenOff(BigDecimal paid, BigDecimal taxRate) {
        BigDecimal left = payable(taxRate).subtract(paid);
        return !isCreditNote() && paid.signum() > 0 && left.signum() > 0 && left.compareTo(WRITE_OFF_LIMIT) < 0
                ? left.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
    }

    /** The least a part payment may be at the tax rate: {@link #MINIMUM_PART_SHARE} of the grand total. */
    public BigDecimal minimumPart(BigDecimal taxRate) {
        return total(taxRate).multiply(MINIMUM_PART_SHARE).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Whether paying the amount on top of what was already paid is too small a part payment: under
     * {@link #minimumPart} while leaving something still due (what would be written off counts as settled).
     */
    public boolean isTooSmallPart(BigDecimal amount, BigDecimal paid, BigDecimal taxRate) {
        return amount.compareTo(minimumPart(taxRate)) < 0 && amountDue(paid.add(amount), taxRate).signum() > 0;
    }

    /** Adds a line to the end of the invoice. */
    public void addLine(InvoiceLine line) {
        lines.add(line);
    }

    /** Whether the invoice has the line with the given id. */
    public boolean hasLine(Long lineId) {
        return lines.stream().anyMatch(l -> lineId.equals(l.getId()));
    }

    /**
     * Removes the line with the given id; whether the invoice had it. Refused when what is left would
     * be less than a fixed discount on the invoice.
     */
    public boolean removeLine(Long lineId) {
        InvoiceLine line = lines.stream().filter(l -> lineId.equals(l.getId())).findFirst().orElse(null);
        if (line == null) {
            return false;
        }
        BigDecimal left = subtotal().subtract(line.amount());
        if (discountAmount.signum() > 0 && left.compareTo(discountAmount) < 0) {
            throw new InvoiceRejectedException("This line cannot be removed: the $"
                    + left.setScale(2).toPlainString() + " left would be less than the $"
                    + discountAmount.setScale(2).toPlainString() + " discount.");
        }
        lines.remove(line);
        return true;
    }

    /**
     * Changes the line with the given id to the given values; whether the invoice had it. Refused when
     * the subtotal would then be less than a fixed discount on the invoice.
     */
    public boolean updateLine(Long lineId, String description, BigDecimal qty, BigDecimal unitPrice,
            boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate) {
        InvoiceLine line = lines.stream().filter(l -> lineId.equals(l.getId())).findFirst().orElse(null);
        if (line == null) {
            return false;
        }
        BigDecimal changed = new InvoiceLine(description, qty, unitPrice, taxExempt, discountPct, taxRate).amount();
        BigDecimal left = subtotal().subtract(line.amount()).add(changed);
        if (discountAmount.signum() > 0 && left.compareTo(discountAmount) < 0) {
            throw new InvoiceRejectedException("This line cannot be changed: the $"
                    + left.setScale(2).toPlainString() + " subtotal would be less than the $"
                    + discountAmount.setScale(2).toPlainString() + " discount.");
        }
        line.update(description, qty, unitPrice, taxExempt, discountPct, taxRate);
        return true;
    }

    /**
     * The sum of the line amounts, each after its own discount and already to the cent — so the subtotal
     * matches what a client gets by adding up the lines shown.
     */
    public BigDecimal subtotal() {
        return lines.stream().map(InvoiceLine::amount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /**
     * The amount taken off: the fixed amount when there is one (never more than the subtotal, should
     * lines have been changed since), otherwise the discount percentage of the subtotal, rounded down
     * to the cent. When there is a cap, it limits everything taken off — the line discounts plus
     * this — so this shrinks to what the line discounts leave under the cap, never below zero.
     */
    public BigDecimal discount() {
        BigDecimal discount = discountAmount.signum() > 0
                ? discountAmount.min(subtotal()).setScale(2, RoundingMode.HALF_UP)
                : subtotal().multiply(discountPct).divide(BigDecimal.valueOf(100)).setScale(2, RoundingMode.DOWN);
        if (discountCap == null) {
            return discount;
        }
        BigDecimal lineDiscounts = lines.stream().map(InvoiceLine::discount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal left = discountCap.setScale(2, RoundingMode.HALF_UP).subtract(lineDiscounts).max(BigDecimal.ZERO.setScale(2));
        return discount.min(left);
    }

    /**
     * Each line's share of the discount, in the order of the lines. The discount's cents are split in
     * proportion to the line amounts: each line first gets the whole cents of its exact share
     * (rounded down), then the cents left over go one each to the lines with the largest leftover
     * fractions; between equal fractions the cent goes to the line with the larger amount, and between
     * equal amounts too to the lower line id. The shares add up to the discount.
     */
    public List<BigDecimal> discountShares() {
        BigInteger discountCents = discount().movePointRight(2).toBigIntegerExact();
        BigInteger subtotalCents = subtotal().movePointRight(2).toBigIntegerExact();
        int n = lines.size();
        BigInteger[] shares = new BigInteger[n];
        BigInteger[] leftovers = new BigInteger[n];
        BigInteger given = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            if (subtotalCents.signum() == 0) {
                shares[i] = BigInteger.ZERO;
                leftovers[i] = BigInteger.ZERO;
            } else {
                // The exact share is discount * amount / subtotal; all fractions share the subtotal
                // as denominator, so the remainders compare as the fractions do.
                BigInteger[] qr = discountCents.multiply(lines.get(i).amount().movePointRight(2).toBigIntegerExact())
                        .divideAndRemainder(subtotalCents);
                shares[i] = qr[0];
                leftovers[i] = qr[1];
            }
            given = given.add(shares[i]);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigInteger>comparing(i -> leftovers[i]).reversed()
                .thenComparing(Comparator.<Integer, BigDecimal>comparing(i -> lines.get(i).amount()).reversed())
                .thenComparing(i -> lines.get(i).getId(), Comparator.nullsLast(Comparator.naturalOrder())));
        int left = discountCents.subtract(given).intValueExact();
        for (int k = 0; k < left; k++) {
            int i = order.get(k);
            shares[i] = shares[i].add(BigInteger.ONE);
        }
        List<BigDecimal> result = new ArrayList<>(n);
        for (BigInteger share : shares) {
            result.add(new BigDecimal(share, 2));
        }
        return result;
    }

    /**
     * Each line's tax, in the order of the lines: the line's own rate (a percentage), or the given
     * workspace rate when it has none, on the line's amount less its share of the discount, rounded
     * half up to the cent. Tax-exempt lines, and every line of a tax-exempt client, have no tax.
     */
    public List<BigDecimal> lineTaxes(BigDecimal taxRate) {
        List<BigDecimal> shares = discountShares();
        List<BigDecimal> taxes = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            InvoiceLine line = lines.get(i);
            taxes.add(clientTaxExempt || line.isTaxExempt() ? BigDecimal.ZERO.setScale(2)
                    : line.amount().subtract(shares.get(i)).multiply(line.taxRate(taxRate)).divide(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP));
        }
        return taxes;
    }

    /** The tax charged at the given rate (a percentage): the sum of the line taxes. */
    public BigDecimal tax(BigDecimal taxRate) {
        return lineTaxes(taxRate).stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** The grand total: the subtotal less the discount, plus tax at the given rate. */
    public BigDecimal total(BigDecimal taxRate) {
        return subtotal().subtract(discount()).add(tax(taxRate));
    }
}
