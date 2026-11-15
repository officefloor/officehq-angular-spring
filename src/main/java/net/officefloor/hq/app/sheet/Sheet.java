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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** A sheet: everything else in the app is kept per sheet. */
@Entity
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
    /** The key of the late percent's dated values. */
    public static final String LATE_PCT = "late-pct";
    /** The key of the cap's dated values. */
    public static final String CAP = "cap";

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

    protected Sheet() {
    }

    public Sheet(String id, String createdBy, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.today = today;
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

    /** The late percent in force on the sheet's today. */
    public BigDecimal getLatePct() {
        return latePctOn(today);
    }

    /** The late percent in force on a date: an overdue claim dated then gains it. */
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

    /** Whether a claim opened now is numbered within its period: the setting is a date and the
     *  sheet's today is on or after it. */
    public boolean isPeriodNumberingOn() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** The cap in force on the sheet's today; null when there is none. */
    public BigDecimal getCap() {
        return amountOn(CAP, today, null);
    }

    /** A due-total held to the cap (null, n/a, is not capped): the cap when the sum is above it. */
    public static BigDecimal capped(BigDecimal dueTotal, BigDecimal cap) {
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
    }

    /** Take the sheet's next claim number. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
