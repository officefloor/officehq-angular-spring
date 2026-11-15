package net.officefloor.hq.app.sheet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.officefloor.hq.app.history.HistoryListener;

/** A sheet: everything else in the app is kept per sheet. */
@Entity
@EntityListeners(HistoryListener.class)
@Table(name = "sheet")
public class Sheet {

    /** The floor a sheet starts with. */
    public static final BigDecimal DEFAULT_FLOOR = new BigDecimal("1.00");

    /** The terms (in days) a sheet starts with. */
    public static final int DEFAULT_TERMS = 14;

    /** The late percent a sheet starts with. */
    public static final BigDecimal DEFAULT_LATE_PCT = new BigDecimal("0.00");

    /** The key of the clerk limit's dated values. */
    public static final String CLERK_LIMIT = "clerk-limit";
    /** The key of the floor's dated values. */
    public static final String FLOOR = "floor";
    /** The key of the sheet's terms' dated values. */
    public static final String TERMS = "terms";
    /** The key of a grade's own terms' dated values, followed by the grade. */
    public static final String TERMS_OF_GRADE = "terms-";
    /** The key of a grade's grace days' dated values, followed by the grade. */
    public static final String GRACE_OF_GRADE = "grace-";
    /** The key of the late percent's dated values. */
    public static final String LATE_PCT = "late-pct";
    /** The key of the cap's dated values. */
    public static final String CAP = "cap";
    /** The key of the proof threshold's dated values. */
    public static final String PROOF_THRESHOLD = "proof-threshold";
    /** The key of a grade's own proof threshold's dated values, followed by the grade. */
    public static final String PROOF_THRESHOLD_OF_GRADE = "proof-threshold-";
    /** The key of the note threshold's dated values. */
    public static final String NOTE_THRESHOLD = "note-threshold";
    /** The key of the rounding mode's dated values. */
    public static final String ROUNDING = "rounding";

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** The sheet's "today": every date decision and default date on the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last number given to a claim opened on the sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The values of the sheet's dated settings (its clerk limit, floor, terms, terms per grade,
     *  late percent and cap), each in force from its from-date until the next one of its key. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting", joinColumns = @JoinColumn(name = "sheet_id"))
    private Set<DatedSetting> settings = new HashSet<>();

    /** A claim dated before this rounds half up, one on or after it half to even; null when blank,
     *  when the sheet's today is the policy date. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** While set and the sheet's today is on or after it, a claim opened is numbered within its
     *  period; null when blank (off), when claims take the sheet-wide numbers. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** While set, a new claim left undated takes this date instead of the sheet's today; null
     *  when blank (off). */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** When the sheet's today changes, every open period whose to-date plus this many days is
     *  before the new today is closed; null when blank (off). */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** The month (1 to 12) the sheet's fiscal year starts in; 1 unless set. Not dated. */
    @Column(name = "fiscal_start_month", nullable = false)
    private int fiscalStartMonth = 1;

    /** The pot shared out among each balanced pool's members by their shares; null when blank
     *  (none). Not dated. */
    @Column(name = "pot")
    private BigDecimal pot;

    /** A change that takes a person's due-total from at or below it (or n/a) to above it alerts;
     *  null when blank (off). Not dated. */
    @Column(name = "alert_threshold")
    private BigDecimal alertThreshold;

    /** The id of the sheet's parent: a grade with no rate of its own in force on a date takes its
     *  parent's (then its parent's parent's, up to three levels up); null when blank (none). Not dated. */
    @Column(name = "parent")
    private String parent;

    /** Whether a steward has archived the sheet: every change on it is refused. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    protected Sheet() {
    }

    public Sheet(String id, String createdBy, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.today = today;
    }

    /** A clone of the sheet under another id, created by a user: its today and every setting (its
     *  dated settings, policy date, period numbering, entry date, grace days, fiscal start month, pot, alert threshold and parent). It is
     *  not archived and has given no claim number yet. */
    public Sheet clonedTo(String id, String createdBy) {
        Sheet copy = new Sheet(id, createdBy, today);
        settings.forEach(d -> copy.settings.add(new DatedSetting(d.getKey(), d.getFrom(), d.getValue())));
        copy.policyDate = policyDate;
        copy.periodNumberingFrom = periodNumberingFrom;
        copy.entryDate = entryDate;
        copy.graceDays = graceDays;
        copy.fiscalStartMonth = fiscalStartMonth;
        copy.pot = pot;
        copy.alertThreshold = alertThreshold;
        copy.parent = parent;
        return copy;
    }

    public String getId() {
        return id;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDate getToday() {
        return today;
    }

    public void setToday(LocalDate today) {
        this.today = today;
    }

    /** The value of a dated setting in force on a date: the one recorded from the latest from-date
     *  on or before it; none before every from-date (the sheet's starting value is in force). */
    public Optional<DatedSetting> settingOn(String key, LocalDate on) {
        return settings.stream()
                .filter(d -> d.getKey().equals(key) && !d.getFrom().isAfter(on))
                .max(Comparator.comparing(DatedSetting::getFrom));
    }

    /** Record a dated setting's value (null: none) from a date, keeping the values recorded from
     *  other dates and replacing one recorded from the same date. */
    public void recordSetting(String key, LocalDate from, String value) {
        settings.removeIf(d -> d.getKey().equals(key) && d.getFrom().equals(from));
        settings.add(new DatedSetting(key, from, value));
    }

    /** Every value recorded for the dated settings, by key, each key's by from-date. */
    public Map<String, List<DatedSetting>> getDatedSettings() {
        Map<String, List<DatedSetting>> out = new TreeMap<>();
        settings.stream()
                .sorted(Comparator.comparing(DatedSetting::getFrom))
                .forEach(d -> out.computeIfAbsent(d.getKey(), k -> new ArrayList<>()).add(d));
        return out;
    }

    private BigDecimal amountOn(String key, LocalDate on, BigDecimal starting) {
        return settingOn(key, on)
                .map(d -> d.getValue() == null ? starting : new BigDecimal(d.getValue()))
                .orElse(starting);
    }

    /** The clerk limit in force on the sheet's today; null when there is none. */
    public BigDecimal getClerkLimit() {
        return amountOn(CLERK_LIMIT, today, null);
    }

    /** The floor in force on the sheet's today. */
    public BigDecimal getFloor() {
        return floorOn(today);
    }

    /** The floor in force on a date: a claim dated then has a live due of at least it. */
    public BigDecimal floorOn(LocalDate on) {
        return amountOn(FLOOR, on, DEFAULT_FLOOR);
    }

    /** The sheet's terms in force on the sheet's today. */
    public int getTerms() {
        return termsOn(today);
    }

    /** The sheet's terms in force on a date. */
    public int termsOn(LocalDate on) {
        return settingOn(TERMS, on).map(DatedSetting::getValue).map(Integer::valueOf).orElse(DEFAULT_TERMS);
    }

    /** The terms of a grade's own in force on the sheet's today; null when it has none then (it
     *  uses the sheet's terms). */
    public Integer getGradeTerms(String grade) {
        return gradeTermsOn(grade, today);
    }

    private Integer gradeTermsOn(String grade, LocalDate on) {
        return settingOn(TERMS_OF_GRADE + grade, on).map(DatedSetting::getValue).map(Integer::valueOf).orElse(null);
    }

    /** The terms in force on a date for a grade: its own then, else the sheet's then. */
    public int termsFor(String grade, LocalDate on) {
        Integer own = gradeTermsOn(grade, on);
        return own != null ? own : termsOn(on);
    }

    /** The grace days of a grade's in force on the sheet's today; null when it has none then. */
    public Integer getGradeGrace(String grade) {
        return gradeGraceOn(grade, today);
    }

    private Integer gradeGraceOn(String grade, LocalDate on) {
        return settingOn(GRACE_OF_GRADE + grade, on).map(DatedSetting::getValue).map(Integer::valueOf).orElse(null);
    }

    /** The grace days in force on a date for a grade: a claim dated then in it is overdue only once
     *  its due-by plus them is before the sheet's today; 0 when it has none then. */
    public int graceOn(String grade, LocalDate on) {
        Integer own = gradeGraceOn(grade, on);
        return own != null ? own : 0;
    }

    /** The late percent in force on the sheet's today. */
    public BigDecimal getLatePct() {
        return latePctOn(today);
    }

    /** The late percent in force on a date: an overdue claim gains it for a 30-day boundary falling then. */
    public BigDecimal latePctOn(LocalDate on) {
        return amountOn(LATE_PCT, on, DEFAULT_LATE_PCT);
    }

    public LocalDate getPolicyDate() {
        return policyDate;
    }

    public void setPolicyDate(LocalDate policyDate) {
        this.policyDate = policyDate;
    }

    /** The policy date in force: the one set, else the sheet's today. */
    public LocalDate getPolicyDateInForce() {
        return policyDate != null ? policyDate : today;
    }

    public LocalDate getPeriodNumberingFrom() {
        return periodNumberingFrom;
    }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) {
        this.periodNumberingFrom = periodNumberingFrom;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    /** The date a new claim left undated takes: the entry date while set, else the sheet's today. */
    public LocalDate getEntryDateInForce() {
        return entryDate != null ? entryDate : today;
    }

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    public int getFiscalStartMonth() {
        return fiscalStartMonth;
    }

    public void setFiscalStartMonth(int fiscalStartMonth) {
        this.fiscalStartMonth = fiscalStartMonth;
    }

    public BigDecimal getPot() {
        return pot;
    }

    public void setPot(BigDecimal pot) {
        this.pot = pot;
    }

    public BigDecimal getAlertThreshold() {
        return alertThreshold;
    }

    public void setAlertThreshold(BigDecimal alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public boolean isArchived() {
        return archived;
    }

    public void archive() {
        this.archived = true;
    }

    /** Whether a claim opened now is numbered within its period: the setting is a date and the
     *  sheet's today is on or after it. */
    public boolean isPeriodNumberingOn() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** The cap in force on the sheet's today; null when there is none. */
    public BigDecimal getCap() {
        return amountOn(CAP, today, null);
    }

    /** The proof threshold in force on the sheet's today; null when there is none. */
    public BigDecimal getProofThreshold() {
        return proofThresholdOn(today);
    }

    /** The proof threshold in force on a date: a claim dated then whose due is above it cannot be
     *  settled or approved without a proof; null when there is none. */
    public BigDecimal proofThresholdOn(LocalDate on) {
        return amountOn(PROOF_THRESHOLD, on, null);
    }

    /** The proof threshold in force on a date for a grade: its own then when one is set, else the
     *  sheet's then; null when there is none. */
    public BigDecimal proofThresholdFor(String grade, LocalDate on) {
        BigDecimal own = grade == null ? null : amountOn(PROOF_THRESHOLD_OF_GRADE + grade, on, null);
        return own != null ? own : proofThresholdOn(on);
    }

    /** The note threshold in force on the sheet's today; null when there is none. */
    public BigDecimal getNoteThreshold() {
        return noteThresholdOn(today);
    }

    /** The note threshold in force on a date: a claim dated then whose due is above it cannot be
     *  opened with an empty note; null when there is none. */
    public BigDecimal noteThresholdOn(LocalDate on) {
        return amountOn(NOTE_THRESHOLD, on, null);
    }

    /** The rounding mode in force on the sheet's today (half-up or half-even); null when there is none. */
    public String getRounding() {
        return roundingOn(today);
    }

    /** The rounding mode in force on a date: a claim dated then rounds its live figures by it;
     *  null when there is none (the claim rounds by the policy date). */
    public String roundingOn(LocalDate on) {
        return settingOn(ROUNDING, on).map(DatedSetting::getValue).orElse(null);
    }

    /** A due-total held to the cap (null, n/a, is not capped): the cap when the sum is above it. */
    public static BigDecimal capped(BigDecimal dueTotal, BigDecimal cap) {
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
    }

    /** The last claim number the sheet gave (0 when none). */
    public int getLastClaimNumber() {
        return lastClaimNumber;
    }
}
