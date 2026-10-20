package net.officefloor.hq.app.sheet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A sheet, kept by the id typed to load it. */
@Entity
@Table(name = "sheet")
public class Sheet {

    /** The keys the dated settings are kept by; a grade's own terms are kept by {@link #gradeTermsKey}. */
    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String FLOOR = "floor";
    public static final String TERMS = "terms";
    public static final String LATE_PCT = "late-pct";
    public static final String CAP = "cap";
    public static final String PROOF_THRESHOLD = "proof-threshold";

    /** The values a sheet starts with, in force before every from-date. */
    public static final BigDecimal STARTING_FLOOR = new BigDecimal("1.00");
    public static final int STARTING_TERMS = 14;
    public static final BigDecimal STARTING_LATE_PCT = new BigDecimal("0.00");

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's own today: every date-dependent decision on the sheet uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last number given to a claim opened on the sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The values of the settings that carry a figure (the clerk limit, the floor, the terms and each grade's,
     * the late percent, the cap and the proof threshold), each from its from-date until the next of the same key. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting_from", joinColumns = @JoinColumn(name = "sheet_id"))
    private List<SettingFrom> dated = new ArrayList<>();

    /** A claim dated before it rounds half up, one dated on or after it half to even; none means the sheet's
     * today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** While a date and the sheet's today is on or after it, a claim opened is numbered within its period; none
     * means off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** When the sheet's today is changed, every open period whose to-date plus these days is before it is
     * closed; none means off. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** The sheet's permission matrix: by action (e.g. "open"), the roles that may take it, comma separated; an
     * action not in it keeps the standing rule. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_permission", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "action")
    @Column(name = "roles", nullable = false)
    private Map<String, String> permissions = new HashMap<>();

    protected Sheet() {}

    public Sheet(String id, String createdBy, Instant createdAt) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.today = LocalDate.ofInstant(createdAt, ZoneId.systemDefault());
    }

    public String getId() {
        return id;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public LocalDate getToday() {
        return today;
    }

    public void setToday(LocalDate today) {
        this.today = today;
    }

    /** The values a dated setting has, by from-date, the one with no from-date first. */
    public List<SettingFrom> history(String key) {
        return dated.stream().filter(d -> d.getKey().equals(key)).sorted(SettingFrom.BY_FROM).toList();
    }

    /** The value a dated setting has in force on a date: the one with the latest from-date on or before it,
     * else {@code starting}, the value a sheet starts with; none where the setting allows it. */
    public BigDecimal valueOn(String key, LocalDate date, BigDecimal starting) {
        return history(key).stream()
                .filter(d -> d.getFrom() == null || !d.getFrom().isAfter(date))
                .max(SettingFrom.BY_FROM)
                .map(SettingFrom::getValue)
                .orElse(starting);
    }

    /** Record the value a dated setting has from a date, replacing one already recorded from that date. */
    public void record(String key, LocalDate from, BigDecimal value) {
        dated.removeIf(d -> d.getKey().equals(key) && Objects.equals(d.getFrom(), from));
        dated.add(new SettingFrom(key, from, value));
    }

    /** The highest due a clerk may settle in force on the sheet's today; none means no limit. */
    public BigDecimal getClerkLimit() {
        return valueOn(CLERK_LIMIT, today, null);
    }

    /** The least a claim's live due comes to in force on a date. */
    public BigDecimal floorOn(LocalDate date) {
        return valueOn(FLOOR, date, STARTING_FLOOR).setScale(2);
    }

    /** The floor in force on the sheet's today. */
    public BigDecimal getFloor() {
        return floorOn(today);
    }

    /** The sheet's own terms in force on a date: the days after its date a claim is due by. */
    public int termsOn(LocalDate date) {
        return valueOn(TERMS, date, BigDecimal.valueOf(STARTING_TERMS)).intValue();
    }

    /** The sheet's own terms in force on the sheet's today. */
    public int getTerms() {
        return termsOn(today);
    }

    /** The grade's own terms in force on the sheet's today; none when it uses the sheet's. */
    public Integer getGradeTerms(String grade) {
        BigDecimal days = valueOn(gradeTermsKey(grade), today, null);
        return days != null ? days.intValue() : null;
    }

    /** The terms a claim of a person of the grade dated on a date is due by: the grade's own in force then, else
     * the sheet's in force then. */
    public int termsFor(String grade, LocalDate date) {
        BigDecimal days = valueOn(gradeTermsKey(grade), date, null);
        return days != null ? days.intValue() : termsOn(date);
    }

    /** The key a grade's own terms are kept by. */
    public static String gradeTermsKey(String grade) {
        return TERMS + "-" + grade;
    }

    /** The percent of its figure an overdue claim dated on a date gains for every full 30 days it is overdue. */
    public BigDecimal latePctOn(LocalDate date) {
        return valueOn(LATE_PCT, date, STARTING_LATE_PCT).setScale(2);
    }

    /** The late percent in force on the sheet's today. */
    public BigDecimal getLatePct() {
        return latePctOn(today);
    }

    /** The roles the sheet gives an action, comma separated; none when it keeps the standing rule. */
    public String getPermission(String action) {
        return permissions.get(action);
    }

    /** Give an action the roles that may take it, comma separated; none (or blank) for the standing rule. */
    public void setPermission(String action, String roles) {
        if (roles == null || roles.isEmpty()) {
            permissions.remove(action);
        } else {
            permissions.put(action, roles);
        }
    }

    public LocalDate getPolicyDate() {
        return policyDate;
    }

    public void setPolicyDate(LocalDate policyDate) {
        this.policyDate = policyDate;
    }

    public LocalDate getPeriodNumberingFrom() {
        return periodNumberingFrom;
    }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) {
        this.periodNumberingFrom = periodNumberingFrom;
    }

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    /** The most a person's due-total comes to in force on the sheet's today; none means no cap. */
    public BigDecimal getCap() {
        return valueOn(CAP, today, null);
    }

    /** A person's due-total held to the cap: the cap when it is above it, else as it is; none (n/a) stays none. */
    public BigDecimal capped(BigDecimal dueTotal) {
        BigDecimal cap = getCap();
        return dueTotal != null && cap != null && dueTotal.compareTo(cap) > 0 ? cap.setScale(2) : dueTotal;
    }

    /** The due above which a claim dated on a date needs a proof before it is settled or approved, in force on
     * that date; none means no threshold. */
    public BigDecimal proofThresholdOn(LocalDate date) {
        return valueOn(PROOF_THRESHOLD, date, null);
    }

    /** The proof threshold in force on the sheet's today; none means no threshold. */
    public BigDecimal getProofThreshold() {
        return proofThresholdOn(today);
    }

    /** Whether a claim opened now is numbered within its period: the period numbering date is set and the
     * sheet's today is on or after it. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** The date the claim rounding goes by: the policy date, or the sheet's today when there is none. */
    public LocalDate roundingDate() {
        return policyDate != null ? policyDate : today;
    }
}
