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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** A sheet: the unit everything else in the app is kept under. Created on its first load. */
@Entity
@Table(name = "sheet")
public class Sheet {

    /** The floor a sheet starts with. */
    public static final BigDecimal DEFAULT_FLOOR = new BigDecimal("1.00");

    /** The terms a sheet starts with, in days. */
    public static final int DEFAULT_TERMS = 14;

    /** The late percent a sheet starts with. */
    public static final BigDecimal DEFAULT_LATE_PCT = new BigDecimal("0.00");

    public static final String CLERK_LIMIT = "clerk-limit";
    /** The key of a grade's own clerk limit: {@code clerk-limit-<grade>}. */
    public static final String GRADE_CLERK_LIMIT = "clerk-limit-";
    public static final String FLOOR = "floor";
    public static final String CAP = "cap";
    public static final String TERMS = "terms";
    /** The key of a grade's own terms: {@code terms-<grade>}. */
    public static final String GRADE_TERMS = "terms-";
    /** The key of a grade's grace days: {@code grace-<grade>}. */
    public static final String GRADE_GRACE = "grace-";
    public static final String LATE_PCT = "late-pct";
    public static final String PROOF_THRESHOLD = "proof-threshold";
    public static final String NOTE_THRESHOLD = "note-threshold";
    public static final String REVIEW_THRESHOLD = "review-threshold";
    /** The key of a grade's own proof threshold: {@code proof-threshold-<grade>}. */
    public static final String GRADE_PROOF_THRESHOLD = "proof-threshold-";
    public static final String ROUNDING = "rounding";
    /** The rounding modes, by the code each is stored under in its dated setting's figure (1, 2, ...). */
    public static final List<String> ROUNDING_MODES = List.of("half-up", "half-even");

    /** The actions the permission matrix covers. */
    public static final List<String> PERMISSION_ACTIONS = List.of("open", "settle", "void", "reassign", "close", "reopen");

    /** The standing rule for an action with no entry in the permission matrix: the roles that may take it. */
    private static final Map<String, Set<String>> STANDING_PERMISSIONS = Map.of(
            "open", Set.of("clerk", "steward"), "settle", Set.of("clerk", "steward"),
            "void", Set.of("clerk", "steward"), "close", Set.of("clerk", "steward"),
            "reassign", Set.of("steward"), "reopen", Set.of("steward"));

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: every date-dependent decision and default date uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number given on the sheet; the next claim opened takes the one after. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The number of the sheet's last settle batch; 0 when there has been none. */
    @Column(name = "last_batch", nullable = false)
    private int lastBatch;

    /**
     * The values recorded for the settings that carry a figure (the floor, the late percent, the
     * terms, a grade's own terms, a grade's grace days, the clerk limit, the cap, the proof threshold and the note threshold), each from a from-date.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting", joinColumns = @JoinColumn(name = "sheet_id"))
    private List<DatedSetting> settings = new ArrayList<>();

    /** Every setting value recorded, oldest first, with the sheet's today when it was recorded. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting_log", joinColumns = @JoinColumn(name = "sheet_id"))
    @OrderColumn(name = "seq")
    private List<LoggedSetting> settingLog = new ArrayList<>();

    /** The date a claim's rounding turns on: half up before it, half to even on or after; null for the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The date numbering per period starts: on or after it a claim settled is numbered within its period; null for off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The default date of a new claim in place of the sheet's today; null for off. */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** The days after a period's to-date it is left open once the sheet's today passes it; null for off. */
    @Column(name = "grace_days")
    private Integer graceDays;

    @Column(name = "pot")
    private BigDecimal pot;

    /** The days since a claim's previous reminder at which reminding it escalates it; null for off. Not dated. */
    @Column(name = "escalate_after")
    private Integer escalateAfter;

    /** The score at or above which a person is MID; null for none. Not dated. */
    @Column(name = "score_mid")
    private Integer scoreMid;

    /** The score at or above which a person is HIGH; null for none. Not dated. */
    @Column(name = "score_high")
    private Integer scoreHigh;

    /** The days a claim may wait in REVIEW past the day it went there before it is late; null for none. Not dated. */
    @Column(name = "review_sla")
    private Integer reviewSla;

    /** The kinds of proof a claim above the proof threshold needs one of each of, comma separated; null for none. Not dated. */
    @Column(name = "proof_kinds")
    private String proofKinds;

    /** The grade a new person takes when none is given (quick-add, a blank import grade); null for A. Not dated. */
    @Column(name = "default_grade")
    private String defaultGrade;

    /** The due-total above which a person is alerted; null for off. Not dated. */
    @Column(name = "alert_threshold")
    private BigDecimal alertThreshold;

    /** The month (1 to 12) the sheet's fiscal year starts in. Not dated. */
    @Column(name = "fiscal_start_month", nullable = false)
    private int fiscalStartMonth = 1;

    /** The id of the sheet whose rates stand in for a grade with no rate of its own in force; null for none. */
    @Column(name = "parent_id")
    private String parentId;

    /** The id of the sheet this one was cloned from; null when it was not cloned. Not dated. */
    @Column(name = "source_id")
    private String sourceId;

    /** The last number given in the sheet's X sequence, to claims opened under numbering per period in no period. */
    @Column(name = "last_unperiod_claim_number", nullable = false)
    private int lastUnperiodClaimNumber;

    /** The permission matrix: for an action, the roles that may take it, comma separated; an action without keeps the standing rule. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_permission", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "action")
    @Column(name = "roles", nullable = false)
    private Map<String, String> permissions = new TreeMap<>();

    /** Whether a steward has archived the sheet: it stays visible and every change to it is refused. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    protected Sheet() {
    }

    public Sheet(String id, String createdBy, Instant createdAt, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.today = today;
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

    public boolean isArchived() {
        return archived;
    }

    public void archive() {
        this.archived = true;
    }

    /**
     * Record a setting's value from a from-date, keeping the values already recorded; a value
     * already recorded for the key from that date is replaced. A null value is none.
     */
    public void record(String key, LocalDate fromDate, BigDecimal value) {
        put(key, fromDate, value);
        settingLog.add(new LoggedSetting(key, fromDate, value, today));
    }

    private void put(String key, LocalDate fromDate, BigDecimal value) {
        settings.removeIf(s -> s.getKey().equals(key) && Objects.equals(s.getFromDate(), fromDate));
        settings.add(new DatedSetting(key, fromDate, value));
    }

    /**
     * The sheet as it stood at the end of a day, for working out figures then: that day stands in
     * for its today, and its settings are those recorded while its today was on or before that day.
     * Not to be saved.
     */
    public Sheet asOf(LocalDate day) {
        Sheet then = new Sheet(id, createdBy, createdAt, day);
        settingLog.stream().filter(s -> s.madeBy(day)).forEach(s -> then.put(s.getKey(), s.getFromDate(), s.getValue()));
        then.policyDate = policyDate;
        then.periodNumberingFrom = periodNumberingFrom;
        then.entryDate = entryDate;
        then.graceDays = graceDays;
        then.escalateAfter = escalateAfter;
        then.scoreMid = scoreMid;
        then.scoreHigh = scoreHigh;
        then.reviewSla = reviewSla;
        then.proofKinds = proofKinds;
        then.defaultGrade = defaultGrade;
        then.pot = pot;
        then.alertThreshold = alertThreshold;
        then.fiscalStartMonth = fiscalStartMonth;
        then.parentId = parentId;
        then.sourceId = sourceId;
        then.permissions.putAll(permissions);
        then.archived = archived;
        return then;
    }

    /** The values recorded for the settings, by key and then by from-date (from the beginning of time first). */
    public List<DatedSetting> getSettings() {
        return settings.stream().sorted(Comparator.comparing(DatedSetting::getKey).thenComparing(
                DatedSetting::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }

    /**
     * The value of a setting recorded with the latest from-date on or before the date (one from the
     * beginning of time counting as the earliest); empty when none is recorded by then.
     */
    private Optional<DatedSetting> inForce(String key, LocalDate date) {
        return settings.stream().filter(s -> s.getKey().equals(key))
                .filter(s -> s.getFromDate() == null || date == null || !s.getFromDate().isAfter(date))
                .max(Comparator.comparing(DatedSetting::getFromDate,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /** The clerk limit in force on the sheet's today: the most a clerk may settle a claim's due at; null for no limit. */
    public BigDecimal getClerkLimit() {
        return inForce(CLERK_LIMIT, today).map(DatedSetting::getValue).orElse(null);
    }

    /**
     * The clerk limit for a grade in force on the sheet's today: the grade's own in force then when it
     * has one (a blank one is none), else the sheet's; null for no limit.
     */
    public BigDecimal clerkLimitFor(String grade) {
        return (grade == null ? Optional.<DatedSetting>empty() : inForce(GRADE_CLERK_LIMIT + grade, today))
                .map(DatedSetting::getValue).orElseGet(this::getClerkLimit);
    }

    /** The floor in force on the sheet's today. */
    public BigDecimal getFloor() {
        return floorOn(today);
    }

    /** The floor in force on a date: the least the live due of a claim of that date can be. */
    public BigDecimal floorOn(LocalDate date) {
        return inForce(FLOOR, date).map(DatedSetting::getValue).orElse(DEFAULT_FLOOR);
    }

    /** The rounding mode in force on the sheet's today ({@code half-up} or {@code half-even}); null for none. */
    public String getRounding() {
        return roundingOn(today);
    }

    /** The rounding mode in force on a date ({@code half-up} or {@code half-even}); null for none. */
    public String roundingOn(LocalDate date) {
        return inForce(ROUNDING, date).map(DatedSetting::getValue).map(Sheet::roundingName).orElse(null);
    }

    /** A rounding mode's name from the code it is stored under; null for none. */
    public static String roundingName(BigDecimal code) {
        return code == null ? null : ROUNDING_MODES.get(code.intValue() - 1);
    }

    /** The code a rounding mode is stored under; null for none. */
    public static BigDecimal roundingCode(String name) {
        return name == null ? null : BigDecimal.valueOf(ROUNDING_MODES.indexOf(name) + 1).setScale(2);
    }

    /** The cap in force on the sheet's today: the most a person's due-total can be; null for no cap. */
    public BigDecimal getCap() {
        return inForce(CAP, today).map(DatedSetting::getValue).orElse(null);
    }

    /** A person's due-total held to the cap in force on the sheet's today (n/a stays n/a). */
    public BigDecimal capped(BigDecimal dueTotal) {
        BigDecimal cap = getCap();
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
    }

    /** The proof threshold in force on the sheet's today; null for none. */
    public BigDecimal getProofThreshold() {
        return proofThresholdOn(today);
    }

    /**
     * The proof threshold in force on a date: a claim of that date whose due is above it cannot be
     * settled or approved while it has no proofs; null for none.
     */
    public BigDecimal proofThresholdOn(LocalDate date) {
        return inForce(PROOF_THRESHOLD, date).map(DatedSetting::getValue).orElse(null);
    }

    /**
     * The proof threshold for a grade in force on a date: the grade's own in force then when it has
     * one (a blank one is none), else the sheet's in force then; null for none.
     */
    public BigDecimal proofThresholdFor(String grade, LocalDate date) {
        return (grade == null ? Optional.<DatedSetting>empty() : inForce(GRADE_PROOF_THRESHOLD + grade, date))
                .map(DatedSetting::getValue).orElseGet(() -> proofThresholdOn(date));
    }

    /** The note threshold in force on the sheet's today; null for none. */
    public BigDecimal getNoteThreshold() {
        return noteThresholdOn(today);
    }

    /**
     * The note threshold in force on a date: a claim of that date whose due is above it cannot be
     * opened while its note is empty; null for none.
     */
    public BigDecimal noteThresholdOn(LocalDate date) {
        return inForce(NOTE_THRESHOLD, date).map(DatedSetting::getValue).orElse(null);
    }

    /** The review threshold in force on the sheet's today; null for none. */
    public BigDecimal getReviewThreshold() {
        return reviewThresholdOn(today);
    }

    /**
     * The review threshold in force on a date: a claim of that date whose due is above it goes to
     * REVIEW when opened, instead of OPEN; null for none.
     */
    public BigDecimal reviewThresholdOn(LocalDate date) {
        return inForce(REVIEW_THRESHOLD, date).map(DatedSetting::getValue).orElse(null);
    }

    /** The sheet's terms in force on the sheet's today. */
    public int getTerms() {
        return termsOn(today);
    }

    /** The sheet's terms in force on a date. */
    private int termsOn(LocalDate date) {
        return inForce(TERMS, date).map(s -> s.getValue().intValue()).orElse(DEFAULT_TERMS);
    }

    /** The terms in force on the sheet's today for each grade that has its own then, by grade. */
    public Map<String, Integer> getGradeTerms() {
        Map<String, Integer> terms = new TreeMap<>();
        settings.stream().map(DatedSetting::getKey).filter(k -> k.startsWith(GRADE_TERMS)).distinct()
                .forEach(k -> inForce(k, today).ifPresent(s -> terms.put(k.substring(GRADE_TERMS.length()),
                        s.getValue().intValue())));
        return terms;
    }

    /** The terms for a grade on a date: its own in force then, else the sheet's in force then. */
    public int termsFor(String grade, LocalDate date) {
        return (grade == null ? Optional.<DatedSetting>empty() : inForce(GRADE_TERMS + grade, date))
                .map(s -> s.getValue().intValue()).orElseGet(() -> termsOn(date));
    }

    /** The grace days in force on the sheet's today for each grade that has some then, by grade. */
    public Map<String, Integer> getGradeGraces() {
        Map<String, Integer> graces = new TreeMap<>();
        settings.stream().map(DatedSetting::getKey).filter(k -> k.startsWith(GRADE_GRACE)).distinct()
                .forEach(k -> inForce(k, today).filter(s -> s.getValue() != null).ifPresent(
                        s -> graces.put(k.substring(GRADE_GRACE.length()), s.getValue().intValue())));
        return graces;
    }

    /** The grace days for a grade in force on a date; 0 when it has none then. */
    public int graceFor(String grade, LocalDate date) {
        return (grade == null ? Optional.<DatedSetting>empty() : inForce(GRADE_GRACE + grade, date))
                .map(DatedSetting::getValue).map(BigDecimal::intValue).orElse(0);
    }

    /**
     * The date a claim's lateness counts from: its due-by plus the grace days of its person's grade
     * in force on its date (the grade given), as in force on its date. A claim is overdue once this
     * is before the sheet's today, and its late charge's 30-day boundaries are counted from it.
     */
    public LocalDate lateFrom(LocalDate dueBy, LocalDate dated, String grade) {
        return dueBy.plusDays(graceFor(grade, dated));
    }

    /** The late percent in force on the sheet's today. */
    public BigDecimal getLatePct() {
        return latePctOn(today);
    }

    /** The late percent in force on a date: what an overdue claim gains of its figure at a 30-day boundary on that date. */
    public BigDecimal latePctOn(LocalDate date) {
        return inForce(LATE_PCT, date).map(DatedSetting::getValue).orElse(DEFAULT_LATE_PCT);
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

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    /** The default date of a new claim: the entry date when set, else the sheet's today. */
    public LocalDate entryDateOrToday() {
        return entryDate != null ? entryDate : today;
    }

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    /** The id of the parent sheet, whose rates stand in for a grade with no rate in force here; null for none. Not dated. */
    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    /**
     * The id of the sheet this one was cloned from, whose rates (then those of its parents and source) stand in
     * for a grade with no rate in force here nor on this sheet's parents; null when it was not cloned. Not dated.
     */
    public String getSourceId() {
        return sourceId;
    }

    /** The pot cut by each balanced pool that is a member of none; null for none. Not dated. */
    public BigDecimal getPot() {
        return pot;
    }

    public void setPot(BigDecimal pot) {
        this.pot = pot;
    }

    /** The due-total above which a person is alerted (see {@link net.officefloor.hq.app.rollup.RollupService}); null for off. Not dated. */
    public Integer getEscalateAfter() {
        return escalateAfter;
    }

    public void setEscalateAfter(Integer escalateAfter) {
        this.escalateAfter = escalateAfter;
    }

    /** The score at or above which a person is MID; null for none. Not dated. */
    public Integer getScoreMid() {
        return scoreMid;
    }

    public void setScoreMid(Integer scoreMid) {
        this.scoreMid = scoreMid;
    }

    /** The score at or above which a person is HIGH; null for none. Not dated. */
    public Integer getScoreHigh() {
        return scoreHigh;
    }

    public void setScoreHigh(Integer scoreHigh) {
        this.scoreHigh = scoreHigh;
    }

    /** The review SLA in days; null for none. Not dated. */
    public Integer getReviewSla() {
        return reviewSla;
    }

    public void setReviewSla(Integer reviewSla) {
        this.reviewSla = reviewSla;
    }

    /** The proof kinds, comma separated; null for none. Not dated. */
    public String getProofKinds() {
        return proofKinds;
    }

    /** The proof kinds a claim above the proof threshold needs one of each of; empty for none. */
    public List<String> proofKindList() {
        return proofKinds == null ? List.of() : List.of(proofKinds.split(","));
    }

    public void setProofKinds(String proofKinds) {
        this.proofKinds = proofKinds;
    }

    /** The default grade; null for none (A). Not dated. */
    public String getDefaultGrade() {
        return defaultGrade;
    }

    /** The grade a new person takes when none is given: the default grade, else A. */
    public String defaultGradeOrA() {
        return defaultGrade == null ? "A" : defaultGrade;
    }

    public void setDefaultGrade(String defaultGrade) {
        this.defaultGrade = defaultGrade;
    }

    public BigDecimal getAlertThreshold() {
        return alertThreshold;
    }

    public void setAlertThreshold(BigDecimal alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    /** The month (1 to 12) the sheet's fiscal year starts in; 1 unless set. Not dated. */
    public int getFiscalStartMonth() {
        return fiscalStartMonth;
    }

    public void setFiscalStartMonth(int fiscalStartMonth) {
        this.fiscalStartMonth = fiscalStartMonth;
    }

    /**
     * The fiscal year a date falls in, for fiscal years starting in a month (1 to 12): named
     * FY&lt;yyyy&gt; by the calendar year of the fiscal year's first day.
     */
    public static String fiscalYearOf(LocalDate date, int startMonth) {
        return "FY" + (date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1);
    }

    /** Whether a claim settled on the sheet's today is numbered within its period. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** Give out the next number in the sheet's X sequence, for a claim in no period. */
    public int nextUnperiodClaimNumber() {
        return ++lastUnperiodClaimNumber;
    }

    /**
     * Give out the next claim number on the sheet: the one after {@code lastInTree}, the last number
     * given anywhere in the sheet's tree (this sheet's own included).
     */
    public int nextClaimNumber(int lastInTree) {
        lastClaimNumber = Math.max(lastClaimNumber, lastInTree) + 1;
        return lastClaimNumber;
    }

    /** The number of the sheet's last settle batch; 0 when there has been none. */
    public int getLastBatch() {
        return lastBatch;
    }

    /** Take the next settle batch number. */
    public int nextBatch() {
        return ++lastBatch;
    }

    /** The permission matrix entry for each action: the roles as entered, blank for the standing rule. */
    public Map<String, String> getPermissions() {
        Map<String, String> all = new TreeMap<>();
        PERMISSION_ACTIONS.forEach(a -> all.put(a, permissions.getOrDefault(a, "")));
        return all;
    }

    /** Set an action's entry in the permission matrix; blank keeps the standing rule. */
    public void setPermission(String action, String roles) {
        if (roles == null || roles.isBlank()) {
            permissions.remove(action);
        } else {
            permissions.put(action, roles);
        }
    }

    /** Whether a role may take an action: never on an archived sheet, never a viewer; else by the permission matrix entry, else the standing rule. */
    public boolean permits(String action, String role) {
        if (archived || "viewer".equals(role)) {
            return false;
        }
        String entry = permissions.get(action);
        if (entry == null || entry.isBlank()) {
            // a restricted user acts as a clerk; only the people list they see is narrower
            String standing = "restricted".equals(role) ? "clerk" : role;
            return STANDING_PERMISSIONS.getOrDefault(action, Set.of()).contains(standing);
        }
        return Arrays.stream(entry.split(",")).map(String::trim).anyMatch(r -> r.equalsIgnoreCase(role));
    }
}
