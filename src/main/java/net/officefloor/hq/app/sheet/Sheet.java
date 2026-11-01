package net.officefloor.hq.app.sheet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A sheet, keyed by the id a user types; everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    /** The keys of its dated settings; a grade's terms are {@code terms-<grade>}. */
    public static final String FLOOR = "floor", LATE_PCT = "late-pct", TERMS = "terms", CLERK_LIMIT = "clerk-limit",
            CAP = "cap", PROOF_THRESHOLD = "proof-threshold";

    /** The values in force before any is recorded. */
    public static final BigDecimal START_FLOOR = new BigDecimal("1.00"), START_LATE_PCT = new BigDecimal("0.00");
    public static final int START_TERMS = 14;

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: every date decision and default date on the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number given on the sheet; the next claim opened takes the one after it. */
    @Column(name = "last_claim_number", nullable = false)
    private long lastClaimNumber;

    /**
     * The values recorded for its dated settings, each from a from-date: the floor (a claim's live due
     * is at least the one in force on its date), the late percent (an overdue claim's due gains the one
     * in force on its date of its figure for every full 30 days overdue), the terms of the sheet and of
     * each grade (a claim's due-by is its date plus those in force on it), the clerk limit (a clerk can
     * settle a claim only when its due is at or below the one in force today) and the cap (a person's
     * due-total is at most the one in force today).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting", joinColumns = @JoinColumn(name = "sheet_id"))
    private List<DatedSetting> dated = new ArrayList<>();

    /** A claim dated before it rounds half up, one on or after it half to even; null means the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** While set and today is on or after it, a claim opened is numbered within its period; null means off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    protected Sheet() {}

    public Sheet(String id, String createdBy, Instant createdAt, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.today = today;
    }

    public String getId() { return id; }

    public String getCreatedBy() { return createdBy; }

    public Instant getCreatedAt() { return createdAt; }

    public LocalDate getToday() { return today; }

    public void setToday(LocalDate today) { this.today = today; }

    /** The values recorded for its dated settings, by key and then from-date, earliest first. */
    public List<DatedSetting> getDated() {
        List<DatedSetting> sorted = new ArrayList<>(dated);
        sorted.sort(Comparator.comparing(DatedSetting::getKey).thenComparing(DatedSetting::getFrom));
        return sorted;
    }

    /** Record the key's value (null: none) from the date on; a value already recorded from that date is replaced. */
    public void record(String key, LocalDate from, String value) {
        dated.removeIf(d -> d.getKey().equals(key) && d.getFrom().equals(from));
        dated.add(new DatedSetting(key, from, value));
    }

    /** The key's value recorded with the latest from-date on or before the date; null when none is. */
    private DatedSetting inForce(String key, LocalDate date) {
        DatedSetting found = null;
        for (DatedSetting d : dated) {
            if (d.getKey().equals(key) && !d.getFrom().isAfter(date)
                    && (found == null || d.getFrom().isAfter(found.getFrom()))) {
                found = d;
            }
        }
        return found;
    }

    private BigDecimal decimalOn(String key, LocalDate date, BigDecimal start) {
        DatedSetting d = inForce(key, date);
        if (d == null) return start;
        return d.getValue() == null ? null : new BigDecimal(d.getValue()).setScale(2);
    }

    /** The clerk limit in force today; null means no limit. */
    public BigDecimal getClerkLimit() { return decimalOn(CLERK_LIMIT, today, null); }

    /** The floor in force on the date: 1.00 before any is recorded. */
    public BigDecimal floorOn(LocalDate date) { return decimalOn(FLOOR, date, START_FLOOR); }

    /** The cap in force today; null means none. */
    public BigDecimal getCap() { return decimalOn(CAP, today, null); }

    /** The proof threshold in force on the date; null means none. */
    public BigDecimal proofThresholdOn(LocalDate date) { return decimalOn(PROOF_THRESHOLD, date, null); }

    /** The due-total, at most the cap in force today; null (n/a) stays null. */
    public BigDecimal capped(BigDecimal dueTotal) {
        BigDecimal cap = getCap();
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
    }

    /** The sheet's terms in force on the date: 14 before any are recorded. */
    public int termsOn(LocalDate date) {
        DatedSetting d = inForce(TERMS, date);
        return d == null ? START_TERMS : Integer.parseInt(d.getValue());
    }

    /** The grade's own terms in force on the date; null when it has none then. */
    public Integer gradeTermsOn(String grade, LocalDate date) {
        DatedSetting d = inForce(TERMS + "-" + grade, date);
        return d == null ? null : Integer.valueOf(d.getValue());
    }

    /** The terms a claim of the grade dated on the date is given: the grade's own then, else the sheet's then. */
    public int termsFor(String grade, LocalDate date) {
        Integer own = gradeTermsOn(grade, date);
        return own != null ? own : termsOn(date);
    }

    /** The late percent in force on the date: 0.00 before any is recorded. */
    public BigDecimal latePctOn(LocalDate date) { return decimalOn(LATE_PCT, date, START_LATE_PCT); }

    public LocalDate getPolicyDate() { return policyDate; }

    public void setPolicyDate(LocalDate policyDate) { this.policyDate = policyDate; }

    public LocalDate getPeriodNumberingFrom() { return periodNumberingFrom; }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) { this.periodNumberingFrom = periodNumberingFrom; }

    /** Whether a claim opened now is numbered within its period rather than sheet-wide. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** Take the next claim number on the sheet. */
    public long nextClaimNumber() { return ++lastClaimNumber; }
}
