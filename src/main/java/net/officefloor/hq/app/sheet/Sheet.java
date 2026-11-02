package net.officefloor.hq.app.sheet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** A sheet, keyed by the id a user types; everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    /**
     * The keys of its dated settings; a grade's terms are {@code terms-<grade>}, its grace days {@code grace-<grade>},
     * its proof threshold {@code proof-threshold-<grade>}, its clerk limit {@code clerk-limit-<grade>}.
     */
    public static final String FLOOR = "floor", LATE_PCT = "late-pct", TERMS = "terms", CLERK_LIMIT = "clerk-limit",
            CAP = "cap", PROOF_THRESHOLD = "proof-threshold", NOTE_THRESHOLD = "note-threshold", GRACE = "grace",
            REVIEW_THRESHOLD = "review-threshold",
            ROUNDING = "rounding";

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
     * is at least the one in force on its date), the late percent (an overdue claim's due gains, for each full 30 days overdue,
     * the one in force on that boundary date of its figure), the terms of the sheet and of
     * each grade (a claim's due-by is its date plus those in force on it), the clerk limit (a clerk can
     * settle a claim only when its due is at or below the one in force today) and the cap (a person's
     * due-total is at most the one in force today), and the grace days of each grade (a claim whose person's
     * grade on its date has them in force then is overdue, and charged late, from its due-by plus them).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting", joinColumns = @JoinColumn(name = "sheet_id"))
    private List<DatedSetting> dated = new ArrayList<>();

    /** Each value recorded for its dated settings, oldest first, with the sheet's today it was recorded on. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting_log", joinColumns = @JoinColumn(name = "sheet_id"))
    @OrderColumn(name = "seq")
    private List<DatedSettingLine> datedLog = new ArrayList<>();

    /**
     * The roles that may take each action whose standing rule was replaced, comma separated, by action
     * (open, settle, void, reassign, close, reopen); an action not in it keeps the standing rule.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_permission", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "perm_action")
    @Column(name = "perm_roles", nullable = false)
    private Map<String, String> permissions = new TreeMap<>();

    /** A claim dated before it rounds half up, one on or after it half to even; null means the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** While set and today is on or after it, a claim settled is numbered within its period; null means off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** When its today is changed, an open period whose to-date plus them is before it is closed; null means off. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** Reminding a claim whose previous reminder was at least this many days before today escalates it (not dated); null means off. */
    @Column(name = "escalate_after")
    private Integer escalateAfter;

    /** A REVIEW claim whose review day plus this many days is before today is late for review (not dated); null means none. */
    @Column(name = "review_sla")
    private Integer reviewSla;

    /** Opening a claim is refused when its person already has this many OPEN or PENDING claims (not dated); null means none. */
    @Column(name = "max_open")
    private Integer maxOpen;

    /** A person whose score is at or above this is in band MID, while the high one is set too (not dated); null means blank. */
    @Column(name = "score_mid")
    private Integer scoreMid;

    /** A person whose score is at or above this is in band HIGH, while the mid one is set too (not dated); null means blank. */
    @Column(name = "score_high")
    private Integer scoreHigh;

    /** While on, changing the sheet's today audits a SCORE line per person (not dated); false means blank. */
    @Column(name = "score_log", nullable = false)
    private boolean scoreLog;

    /** The month (1 to 12) a fiscal year starts in (not dated); 1 unless set. */
    @Column(name = "fiscal_start_month", nullable = false)
    private int fiscalStartMonth = 1;

    /** Split across each balanced pool's members as their cuts (not dated); null means none. */
    @Column(name = "pot", precision = 14, scale = 2)
    private BigDecimal pot;

    /** A change taking a person's due-total from at or below it (or none) to above it is alerted (not dated); null means off. */
    @Column(name = "alert_threshold", precision = 14, scale = 2)
    private BigDecimal alertThreshold;

    /** The proof kinds (not dated), comma separated: a claim above the proof threshold needs a proof of each; null means none. */
    @Column(name = "proof_kinds", length = 100)
    private String proofKinds;

    /** The default grade (not dated): quick-add gives a person it adds it, and an import line with a blank grade takes it; null means A. */
    @Column(name = "default_grade", length = 1)
    private String defaultGrade;

    /** The grades retired on the sheet (not dated): none can be chosen for a new person or in an edit. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_retired_grade", joinColumns = @JoinColumn(name = "sheet_id"))
    @Column(name = "grade", nullable = false, length = 1)
    private Set<String> retiredGrades = new TreeSet<>();

    /** While set, a new claim given no date takes it rather than the sheet's today; null means off. */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** Set once a steward archives the sheet: it stays visible and every change to it is refused. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    /** The number of its last settle batch; 0 while there has been none. */
    @Column(name = "last_settle_batch", nullable = false)
    private int lastSettleBatch;

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
        datedLog.add(new DatedSettingLine(key, from, value, today));
    }

    /**
     * The key's value in force on the date as recorded by the end of the as-of date: of the values recorded
     * while the sheet's today was on or before it, the last one with the latest from-date on or before the date.
     */
    private DatedSettingLine inForce(String key, LocalDate date, LocalDate asOf) {
        DatedSettingLine found = null;
        for (DatedSettingLine d : datedLog) {
            if (d.getKey().equals(key) && d.recordedBy(asOf) && !d.getFrom().isAfter(date)
                    && (found == null || !d.getFrom().isBefore(found.getFrom()))) {
                found = d;
            }
        }
        return found;
    }

    private BigDecimal decimalOn(String key, LocalDate date, LocalDate asOf, BigDecimal start) {
        DatedSettingLine d = inForce(key, date, asOf);
        if (d == null) return start;
        return d.getValue() == null ? null : new BigDecimal(d.getValue()).setScale(2);
    }

    /** The floor in force on the date as recorded by the end of the as-of date. */
    public BigDecimal floorOn(LocalDate date, LocalDate asOf) { return decimalOn(FLOOR, date, asOf, START_FLOOR); }

    /** The late percent in force on the date as recorded by the end of the as-of date. */
    public BigDecimal latePctOn(LocalDate date, LocalDate asOf) { return decimalOn(LATE_PCT, date, asOf, START_LATE_PCT); }

    /** The grade's grace days in force on the date as recorded by the end of the as-of date; 0 when none. */
    public int graceOn(String grade, LocalDate date, LocalDate asOf) {
        DatedSettingLine d = inForce(GRACE + "-" + grade, date, asOf);
        return d == null || d.getValue() == null ? 0 : Integer.parseInt(d.getValue());
    }

    /** The rounding mode in force on the date as recorded by the end of the as-of date; null when none. */
    public RoundingMode roundingOn(LocalDate date, LocalDate asOf) {
        DatedSettingLine d = inForce(ROUNDING, date, asOf);
        return d == null ? null : roundingMode(d.getValue());
    }

    /** The due-total, at most the cap in force on the as-of date as recorded by its end; null (n/a) stays null. */
    public BigDecimal cappedAsOf(BigDecimal dueTotal, LocalDate asOf) {
        BigDecimal cap = decimalOn(CAP, asOf, asOf, null);
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
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

    /** The rounding mode in force on the date; null when none, when the policy date decides. */
    public RoundingMode roundingOn(LocalDate date) {
        DatedSetting d = inForce(ROUNDING, date);
        return d == null ? null : roundingMode(d.getValue());
    }

    /** A rounding mode as recorded: half-up or half-even; null (none) otherwise. */
    private static RoundingMode roundingMode(String value) {
        if ("half-up".equals(value)) return RoundingMode.HALF_UP;
        if ("half-even".equals(value)) return RoundingMode.HALF_EVEN;
        return null;
    }

    /** The clerk limit in force today; null means no limit. */
    public BigDecimal getClerkLimit() { return decimalOn(CLERK_LIMIT, today, null); }

    /** The grade's own clerk limit in force today; null means none (the sheet's applies). */
    public BigDecimal gradeClerkLimit(String grade) { return decimalOn(CLERK_LIMIT + "-" + grade, today, null); }

    /** The clerk limit in force today for a claim of the grade: the grade's own, else the sheet's; null means no limit. */
    public BigDecimal clerkLimitFor(String grade) {
        BigDecimal own = gradeClerkLimit(grade);
        return own != null ? own : getClerkLimit();
    }

    /** The floor in force on the date: 1.00 before any is recorded. */
    public BigDecimal floorOn(LocalDate date) { return decimalOn(FLOOR, date, START_FLOOR); }

    /** The cap in force today; null means none. */
    public BigDecimal getCap() { return decimalOn(CAP, today, null); }

    /** The proof threshold in force on the date; null means none. */
    public BigDecimal proofThresholdOn(LocalDate date) { return decimalOn(PROOF_THRESHOLD, date, null); }

    /** The grade's own proof threshold in force on the date; null when it has none then (none recorded, or blank). */
    public BigDecimal gradeProofThresholdOn(String grade, LocalDate date) {
        return decimalOn(PROOF_THRESHOLD + "-" + grade, date, null);
    }

    /** The proof threshold for a claim of the grade dated on the date: the grade's own then, else the sheet's then; null means none. */
    public BigDecimal proofThresholdFor(String grade, LocalDate date) {
        BigDecimal own = gradeProofThresholdOn(grade, date);
        return own != null ? own : proofThresholdOn(date);
    }

    /** The note threshold in force on the date; null means none. */
    public BigDecimal noteThresholdOn(LocalDate date) { return decimalOn(NOTE_THRESHOLD, date, null); }

    /** The review threshold in force on the date; null means none. */
    public BigDecimal reviewThresholdOn(LocalDate date) { return decimalOn(REVIEW_THRESHOLD, date, null); }

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

    /** The grade's grace days in force on the date; 0 when it has none then (none recorded, or blank). */
    public int graceOn(String grade, LocalDate date) {
        DatedSetting d = inForce(GRACE + "-" + grade, date);
        return d == null || d.getValue() == null ? 0 : Integer.parseInt(d.getValue());
    }

    /** The late percent in force on the date: 0.00 before any is recorded. */
    public BigDecimal latePctOn(LocalDate date) { return decimalOn(LATE_PCT, date, START_LATE_PCT); }

    /** The roles set for each action, comma separated, by action; an action not in it keeps the standing rule. */
    public Map<String, String> getPermissions() { return new TreeMap<>(permissions); }

    /** Set the roles that may take the action, comma separated; blank or null: the standing rule. */
    public void setPermission(String action, String roles) {
        if (roles == null || roles.isEmpty()) permissions.remove(action);
        else permissions.put(action, roles);
    }

    /** The roles set for the action, comma separated; null when it keeps the standing rule. */
    public String permission(String action) { return permissions.get(action); }

    public LocalDate getPolicyDate() { return policyDate; }

    public void setPolicyDate(LocalDate policyDate) { this.policyDate = policyDate; }

    public LocalDate getPeriodNumberingFrom() { return periodNumberingFrom; }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) { this.periodNumberingFrom = periodNumberingFrom; }

    public Integer getGraceDays() { return graceDays; }

    public void setGraceDays(Integer graceDays) { this.graceDays = graceDays; }

    public Integer getEscalateAfter() { return escalateAfter; }

    public Integer getReviewSla() { return reviewSla; }

    public void setReviewSla(Integer reviewSla) { this.reviewSla = reviewSla; }

    public Integer getMaxOpen() { return maxOpen; }

    public void setMaxOpen(Integer maxOpen) { this.maxOpen = maxOpen; }

    public Integer getScoreMid() { return scoreMid; }

    public void setScoreMid(Integer scoreMid) { this.scoreMid = scoreMid; }

    public Integer getScoreHigh() { return scoreHigh; }

    public void setScoreHigh(Integer scoreHigh) { this.scoreHigh = scoreHigh; }

    public boolean isScoreLog() { return scoreLog; }

    public void setScoreLog(boolean scoreLog) { this.scoreLog = scoreLog; }

    public void setEscalateAfter(Integer escalateAfter) { this.escalateAfter = escalateAfter; }
    public int getFiscalStartMonth() { return fiscalStartMonth; }
    public void setFiscalStartMonth(int fiscalStartMonth) { this.fiscalStartMonth = fiscalStartMonth; }

    public BigDecimal getPot() { return pot; }

    public void setPot(BigDecimal pot) { this.pot = pot; }

    public BigDecimal getAlertThreshold() { return alertThreshold; }

    public void setAlertThreshold(BigDecimal alertThreshold) { this.alertThreshold = alertThreshold; }

    public String getProofKinds() { return proofKinds; }

    public void setProofKinds(String proofKinds) { this.proofKinds = proofKinds; }

    public String getDefaultGrade() { return defaultGrade; }

    public void setDefaultGrade(String defaultGrade) { this.defaultGrade = defaultGrade; }

    public Set<String> getRetiredGrades() { return new TreeSet<>(retiredGrades); }

    public boolean isGradeRetired(String grade) { return retiredGrades.contains(grade); }

    public void retireGrade(String grade) { retiredGrades.add(grade); }

    public LocalDate getEntryDate() { return entryDate; }

    public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }

    public boolean isArchived() { return archived; }

    public void archive() { this.archived = true; }

    public int getLastSettleBatch() { return lastSettleBatch; }

    /** Take the next settle batch number on the sheet. */
    public int nextSettleBatch() { return ++lastSettleBatch; }

    /** Whether a claim opened now is numbered within its period rather than sheet-wide. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** Take the next claim number on the sheet. */
    public long nextClaimNumber() { return ++lastClaimNumber; }

    /** The next sheet-wide claim number after both the sheet's last and {@code last}, kept as the sheet's last. */
    public long nextClaimNumberAfter(long last) {
        lastClaimNumber = Math.max(lastClaimNumber, last) + 1;
        return lastClaimNumber;
    }
}
