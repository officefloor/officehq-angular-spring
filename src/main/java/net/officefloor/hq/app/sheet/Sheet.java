package net.officefloor.hq.app.sheet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.role.Role;

/** A sheet: everything else in the app is kept per sheet. It starts empty when first loaded. */
@Entity
@Table(name = "sheet")
public class Sheet {

    /** The keys of the settings that carry a figure, each dated. */
    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String CAP = "cap";
    public static final String FLOOR = "floor";
    public static final String TERMS = "terms";
    public static final String LATE_PCT = "late-pct";
    public static final String PROOF_THRESHOLD = "proof-threshold";
    public static final String NOTE_THRESHOLD = "note-threshold";
    public static final String REVIEW_THRESHOLD = "review-threshold";
    public static final String GRACE = "grace";
    /** The key of the rounding mode: half-up or half-even, dated; none before every from-date. */
    public static final String ROUNDING = "rounding";

    /** The actions the permission matrix covers, each with the roles that may take it under the standing rule. */
    public static final Map<String, String> STANDING_PERMISSIONS = Map.of(
            "open", "clerk,steward", "settle", "clerk,steward", "void", "clerk,steward",
            "reassign", "steward", "close", "clerk,steward", "reopen", "steward");

    @Id
    private String id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: every date-dependent decision on the sheet uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last plain sheet-wide claim number the sheet gave out, before numbering restarted each fiscal year; 0 when none. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The starting clerk limit (before every from-date): a claim a clerk settles whose due is above it goes PENDING for a steward; null for no limit. */
    @Column(name = "clerk_limit")
    private BigDecimal clerkLimit;

    /** The starting cap (before every from-date): a person's due-total is at most it; null for no cap. */
    @Column(name = "cap")
    private BigDecimal cap;

    /** The starting proof threshold (before every from-date): a claim whose due is above it cannot be settled or approved with no proofs; null for none. */
    @Column(name = "proof_threshold")
    private BigDecimal proofThreshold;

    /** The starting floor (before every from-date): a claim's live due is at least it. */
    @Column(name = "floor", nullable = false)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The starting terms (before every from-date): a claim is due by its date plus this many days. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** Grade A's own terms in days; null when it uses the sheet's terms. */
    @Column(name = "terms_a")
    private Integer termsA;

    /** Grade B's own terms in days; null when it uses the sheet's terms. */
    @Column(name = "terms_b")
    private Integer termsB;

    /** Grade C's own terms in days; null when it uses the sheet's terms. */
    @Column(name = "terms_c")
    private Integer termsC;

    /** The starting late percent (before every from-date): an overdue claim's due gains it of its figure for every full 30 days overdue. */
    @Column(name = "late_pct", nullable = false)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** The policy date: a claim dated before it rounds half up, on or after it half to even; null for the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** Period numbering from: a claim opened on or after it is numbered within its period; null for off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** Grace days: when today is changed, an open period whose to-date plus them is before it closes; null for off. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** Entry date: while set, a new claim with no date is dated it rather than the sheet's today; null for off. */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** The pot: split among each balanced pool's members as their cuts; null for none. Not dated. */
    @Column(name = "pot")
    private BigDecimal pot;

    /** The alert threshold: a person whose due-total crosses above it is alerted; null for off. Not dated. */
    @Column(name = "alert_threshold")
    private BigDecimal alertThreshold;

    /** The escalation gap in days: a reminder at least this long after the previous one escalates the claim; null for off. Not dated. */
    @Column(name = "escalate_after")
    private Integer escalateAfter;

    /** The review SLA in days: a REVIEW claim whose review day plus them is before the sheet's today is late; null for none. Not dated. */
    @Column(name = "review_sla")
    private Integer reviewSla;

    /** The most OPEN or PENDING claims a person may have: opening one more is refused; null for none. Not dated. */
    @Column(name = "max_open")
    private Integer maxOpen;

    /** The mid score threshold: a person scoring at or above it is MID (while the high one is set too); null for none. Not dated. */
    @Column(name = "score_mid")
    private Integer scoreMid;

    /** The high score threshold: a person scoring at or above it is HIGH (while the mid one is set too); null for none. Not dated. */
    @Column(name = "score_high")
    private Integer scoreHigh;

    /** Whether the score log is on: changing the sheet's today then audits each person's score. Not dated. */
    @Column(name = "score_log")
    private boolean scoreLog;

    /** The proof kinds: comma separated, a claim above the proof threshold needs a proof of each to be settled or approved; null for none. Not dated. */
    @Column(name = "proof_kinds")
    private String proofKinds;

    /** The month, 1 to 12, the sheet's fiscal year starts in; 1 (January) unless set. Not dated. */
    @Column(name = "fiscal_start_month", nullable = false)
    private int fiscalStartMonth = 1;

    /** The parent sheet's id: its rates apply where this sheet has none in force; null for none. Not dated. */
    @Column(name = "parent_id")
    private String parentId;

    /** The default grade: given to a person added by the quick-add bar or a blank-graded import line; null for A. Not dated. */
    @Column(name = "default_grade")
    private String defaultGrade;

    /**
     * The id of the sheet this one was cloned from: its rates apply where this sheet and its parents
     * have none in force; null when not cloned. Not dated.
     */
    @Column(name = "cloned_from")
    private String clonedFrom;

    /** Archived: everything on the sheet stays visible but nothing on it may change. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    /** The last X-number given to a claim in no period while period numbering is on; 0 before any. */
    @Column(name = "last_unperiod_claim_number", nullable = false)
    private int lastUnperiodClaimNumber;

    /** The last settle batch number the sheet gave out; 0 before any bulk settle settled a claim. */
    @Column(name = "last_settle_batch", nullable = false)
    private int lastSettleBatch;

    /** The values settings carry from a date; before every from-date a setting has its column's value. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting", joinColumns = @JoinColumn(name = "sheet_id"))
    private List<DatedSetting> dated = new ArrayList<>();

    /** The permission matrix: for an action, the comma separated roles that may take it; an action with none keeps its standing rule. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_permission", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "action")
    @Column(name = "roles", nullable = false)
    private Map<String, String> permissions = new HashMap<>();

    /** The last sheet-wide claim number given out in each fiscal year, by the year it is named after. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_fiscal_claim_number", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "fiscal_year")
    @Column(name = "last_number", nullable = false)
    private Map<Integer, Integer> lastFiscalClaimNumbers = new HashMap<>();

    protected Sheet() {
    }

    public Sheet(String id, Instant createdAt, LocalDate today) {
        this.id = id;
        this.createdAt = createdAt;
        this.today = today;
    }

    public String getId() {
        return id;
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

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    /** The clerk limit in force on the sheet's today, with two decimals; null when there is none. */
    public BigDecimal getClerkLimit() {
        return decimal(valueOn(CLERK_LIMIT, today, plain(clerkLimit)));
    }

    /** A grade's clerk limit in force on the sheet's today, with two decimals; null when it has none (the sheet's applies). */
    public BigDecimal getGradeClerkLimit(String grade) {
        return decimal(valueOn(gradeClerkLimitKey(grade), today, null));
    }

    /**
     * The clerk limit for a grade's claims: the grade's own in force on the sheet's today when it has
     * one, else the sheet's; null when there is none.
     */
    public BigDecimal clerkLimitFor(String grade) {
        BigDecimal own = grade == null ? null : getGradeClerkLimit(grade);
        return own != null ? own : getClerkLimit();
    }

    /** The cap in force on the sheet's today, with two decimals; null when there is none. */
    public BigDecimal getCap() {
        return decimal(valueOn(CAP, today, plain(cap)));
    }

    /** A sum of figures held to the cap in force today: the cap when the sum is above it; null (n/a) stays null. */
    public BigDecimal capped(BigDecimal sum) {
        BigDecimal cap = getCap();
        return sum != null && cap != null && sum.compareTo(cap) > 0 ? cap : sum;
    }

    /**
     * A sum of figures held to the cap in force today less a cut total: the cap covers the sum and the cut
     * total, so the sum is at most the cap less the cut total, not below 0.00; null (n/a) stays null.
     */
    public BigDecimal capped(BigDecimal sum, BigDecimal cut) {
        BigDecimal cap = getCap();
        if (sum == null || cap == null) {
            return sum;
        }
        BigDecimal room = cap.subtract(cut).max(BigDecimal.ZERO.setScale(2));
        return sum.compareTo(room) > 0 ? room : sum;
    }

    /** The proof threshold in force on the sheet's today, with two decimals; null when there is none. */
    public BigDecimal getProofThreshold() {
        return proofThresholdOn(today);
    }

    /** The proof threshold in force on a date, with two decimals; null when there is none. */
    public BigDecimal proofThresholdOn(LocalDate date) {
        return decimal(valueOn(PROOF_THRESHOLD, date, plain(proofThreshold)));
    }

    /** A grade's proof threshold in force on the sheet's today, with two decimals; null when it has none. */
    public BigDecimal getGradeProofThreshold(String grade) {
        return gradeProofThresholdOn(grade, today);
    }

    /** A grade's proof threshold in force on a date, with two decimals; null when it has none (as before every from-date). */
    public BigDecimal gradeProofThresholdOn(String grade, LocalDate date) {
        return decimal(valueOn(gradeProofThresholdKey(grade), date, null));
    }

    /**
     * The proof threshold for a grade's claims dated on a date: the grade's own in force then when it
     * has one, else the sheet's; null when there is none.
     */
    public BigDecimal proofThresholdFor(String grade, LocalDate date) {
        BigDecimal own = grade == null ? null : gradeProofThresholdOn(grade, date);
        return own != null ? own : proofThresholdOn(date);
    }

    /** The note threshold in force on the sheet's today, with two decimals; null when there is none. */
    public BigDecimal getNoteThreshold() {
        return noteThresholdOn(today);
    }

    /**
     * The note threshold in force on a date, with two decimals: a claim dated then whose due is above it
     * cannot be opened with an empty note; null when there is none (as before every from-date).
     */
    public BigDecimal noteThresholdOn(LocalDate date) {
        return decimal(valueOn(NOTE_THRESHOLD, date, null));
    }

    /** The review threshold in force on the sheet's today, with two decimals; null when there is none. */
    public BigDecimal getReviewThreshold() {
        return reviewThresholdOn(today);
    }

    /**
     * The review threshold in force on a date, with two decimals: a claim dated then whose due is above
     * it goes to REVIEW when opened; null when there is none (as before every from-date).
     */
    public BigDecimal reviewThresholdOn(LocalDate date) {
        return decimal(valueOn(REVIEW_THRESHOLD, date, null));
    }

    /** The floor in force on the sheet's today, with two decimals. */
    public BigDecimal getFloor() {
        return floorOn(today);
    }

    /** The floor in force on a date, with two decimals. */
    public BigDecimal floorOn(LocalDate date) {
        return decimal(valueOn(FLOOR, date, plain(floor)));
    }

    /** The terms in force on the sheet's today, in days. */
    public int getTerms() {
        return termsOn(today);
    }

    /** The sheet's terms in force on a date, in days. */
    public int termsOn(LocalDate date) {
        return Integer.parseInt(valueOn(TERMS, date, Integer.toString(terms)));
    }

    /** A grade's own terms in force on the sheet's today in days; null when it uses the sheet's terms. */
    public Integer getGradeTerms(String grade) {
        return gradeTermsOn(grade, today);
    }

    /** A grade's own terms in force on a date in days; null when it uses the sheet's terms. */
    public Integer gradeTermsOn(String grade, LocalDate date) {
        Integer start = switch (grade) {
            case "A" -> termsA;
            case "B" -> termsB;
            case "C" -> termsC;
            // A grade added to the sheet has no starting terms of its own.
            default -> null;
        };
        String value = valueOn(gradeTermsKey(grade), date, start == null ? null : start.toString());
        return value == null ? null : Integer.valueOf(value);
    }

    /** The terms a grade's claims dated on a date are due by: its own in force then, else the sheet's. */
    public int termsFor(String grade, LocalDate date) {
        Integer own = grade == null ? null : gradeTermsOn(grade, date);
        return own != null ? own : termsOn(date);
    }

    /** A grade's grace in force on the sheet's today in days; null when it has none. */
    public Integer getGradeGrace(String grade) {
        return gradeGraceOn(grade, today);
    }

    /** A grade's grace in force on a date in days; null when it has none (as before every from-date). */
    public Integer gradeGraceOn(String grade, LocalDate date) {
        String value = valueOn(gradeGraceKey(grade), date, null);
        return value == null ? null : Integer.valueOf(value);
    }

    /**
     * The date a claim's lateness is counted from: its due-by plus the grace its grade (the one in force
     * on the claim's date) has in force on that date, else its due-by. It is overdue once this is before
     * the sheet's today, and its late charge's 30-day boundaries are counted from it.
     */
    public LocalDate lateFrom(LocalDate dueBy, String grade, LocalDate date) {
        Integer grace = grade == null ? null : gradeGraceOn(grade, date);
        return grace == null ? dueBy : dueBy.plusDays(grace);
    }

    /** The late percent in force on the sheet's today, with two decimals. */
    public BigDecimal getLatePct() {
        return latePctOn(today);
    }

    /** The late percent in force on a date, with two decimals. */
    public BigDecimal latePctOn(LocalDate date) {
        return decimal(valueOn(LATE_PCT, date, plain(latePct)));
    }

    /** The key a grade's own terms are recorded under. */
    public static String gradeTermsKey(String grade) {
        return TERMS + "-" + grade;
    }

    /** The key a grade's grace is recorded under. */
    public static String gradeGraceKey(String grade) {
        return GRACE + "-" + grade;
    }

    /** The key a grade's clerk limit is recorded under. */
    public static String gradeClerkLimitKey(String grade) {
        return CLERK_LIMIT + "-" + grade;
    }

    /** The key a grade's proof threshold is recorded under. */
    public static String gradeProofThresholdKey(String grade) {
        return PROOF_THRESHOLD + "-" + grade;
    }

    /** A grade's grace values (see {@link #history}), none before every from-date. */
    public List<DatedSetting> gradeGraceHistory(String grade) {
        return history(gradeGraceKey(grade), null);
    }

    /**
     * Record a setting's value (null: none) from a date, keeping the values already recorded; a value
     * already recorded from that date is replaced.
     */
    public void record(String key, LocalDate from, String value) {
        dated.removeIf(d -> d.getKey().equals(key) && d.getFromDate().equals(from));
        dated.add(new DatedSetting(key, from, value));
    }

    /**
     * A setting's values: its starting value (with no from-date) and then each recorded from a date,
     * by from-date.
     */
    public List<DatedSetting> history(String key, String start) {
        List<DatedSetting> all = new ArrayList<>();
        all.add(new DatedSetting(key, null, start));
        dated.stream().filter(d -> d.getKey().equals(key))
                .sorted(Comparator.comparing(DatedSetting::getFromDate)).forEach(all::add);
        return all;
    }

    /** Every value recorded for a setting from a date, whatever its key. */
    public List<DatedSetting> getDated() {
        return List.copyOf(dated);
    }

    /**
     * The sheet as it stood on a past date, held in no store: its starting values and permissions as
     * now, that date as its today, and the values recorded from a date and the policy date it had then.
     */
    public Sheet asOf(LocalDate day, List<DatedSetting> datedThen, LocalDate policyDateThen) {
        Sheet then = new Sheet(id, createdAt, day);
        then.lastClaimNumber = lastClaimNumber;
        then.clerkLimit = clerkLimit;
        then.cap = cap;
        then.proofThreshold = proofThreshold;
        then.floor = floor;
        then.terms = terms;
        then.termsA = termsA;
        then.termsB = termsB;
        then.termsC = termsC;
        then.latePct = latePct;
        then.policyDate = policyDateThen;
        then.periodNumberingFrom = periodNumberingFrom;
        then.graceDays = graceDays;
        then.entryDate = entryDate;
        then.pot = pot;
        then.alertThreshold = alertThreshold;
        then.escalateAfter = escalateAfter;
        then.reviewSla = reviewSla;
        then.maxOpen = maxOpen;
        then.scoreMid = scoreMid;
        then.scoreHigh = scoreHigh;
        then.scoreLog = scoreLog;
        then.proofKinds = proofKinds;
        then.defaultGrade = defaultGrade;
        then.fiscalStartMonth = fiscalStartMonth;
        then.parentId = parentId;
        then.clonedFrom = clonedFrom;
        then.archived = archived;
        then.dated = new ArrayList<>(datedThen);
        then.permissions = new HashMap<>(permissions);
        then.lastFiscalClaimNumbers = new HashMap<>(lastFiscalClaimNumbers);
        return then;
    }

    /** The floor's values (see {@link #history}). */
    public List<DatedSetting> floorHistory() {
        return history(FLOOR, plain(floor));
    }

    /** The rounding mode's values (see {@link #history}), none before every from-date. */
    public List<DatedSetting> roundingHistory() {
        return history(ROUNDING, null);
    }

    /** The late percent's values (see {@link #history}). */
    public List<DatedSetting> latePctHistory() {
        return history(LATE_PCT, plain(latePct));
    }

    /**
     * A setting's value in force on a date: the one recorded with the latest from-date on or before
     * it (which may be null, for none), else the starting value.
     */
    private String valueOn(String key, LocalDate date, String start) {
        Optional<DatedSetting> recorded = dated.stream()
                .filter(d -> d.getKey().equals(key) && !d.getFromDate().isAfter(date))
                .max(Comparator.comparing(DatedSetting::getFromDate));
        return recorded.isPresent() ? recorded.get().getValue() : start;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    /** The policy date as set; null when blank (the sheet's today is then the policy date). */
    public LocalDate getPolicyDate() {
        return policyDate;
    }

    public void setPolicyDate(LocalDate policyDate) {
        this.policyDate = policyDate;
    }

    /**
     * How the live figures of a claim dated on a date round: by the rounding mode in force on that date
     * when there is one, else half up when the date is before the
     * policy date (the sheet's today when none is set), else half to even.
     */
    public RoundingMode roundingOn(LocalDate date) {
        RoundingMode mode = roundingModeOn(date);
        if (mode != null) {
            return mode;
        }
        LocalDate policy = policyDate == null ? today : policyDate;
        return date.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** The rounding mode in force on the sheet's today (half-up or half-even); null when there is none. */
    public String getRounding() {
        return valueOn(ROUNDING, today, null);
    }

    /** The rounding mode in force on a date; null when there is none (the policy date then decides). */
    public RoundingMode roundingModeOn(LocalDate date) {
        String value = valueOn(ROUNDING, date, null);
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "half-up" -> RoundingMode.HALF_UP;
            case "half-even" -> RoundingMode.HALF_EVEN;
            default -> null;
        };
    }

    /** The period-numbering-from date as set; null when blank (off). */
    public LocalDate getPeriodNumberingFrom() {
        return periodNumberingFrom;
    }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) {
        this.periodNumberingFrom = periodNumberingFrom;
    }

    /** The grace days as set; null when blank (off). */
    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    /** The entry date as set; null when blank (off). */
    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    /** The pot as set, with two decimals; null when blank (none). */
    public BigDecimal getPot() {
        return pot;
    }

    public void setPot(BigDecimal pot) {
        this.pot = pot;
    }

    /** The alert threshold as set, with two decimals; null when blank (off). */
    public BigDecimal getAlertThreshold() {
        return alertThreshold;
    }

    public void setAlertThreshold(BigDecimal alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    /** The escalation gap in days; null when blank (off). */
    public Integer getEscalateAfter() {
        return escalateAfter;
    }

    public void setEscalateAfter(Integer escalateAfter) {
        this.escalateAfter = escalateAfter;
    }

    /** The review SLA in days; null when blank (none). */
    public Integer getReviewSla() {
        return reviewSla;
    }

    public void setReviewSla(Integer reviewSla) {
        this.reviewSla = reviewSla;
    }

    /** The most OPEN or PENDING claims a person may have; null when blank (none). */
    public Integer getMaxOpen() {
        return maxOpen;
    }

    public void setMaxOpen(Integer maxOpen) {
        this.maxOpen = maxOpen;
    }

    /** The mid score threshold; null when blank (none). */
    public Integer getScoreMid() {
        return scoreMid;
    }

    public void setScoreMid(Integer scoreMid) {
        this.scoreMid = scoreMid;
    }

    /** The high score threshold; null when blank (none). */
    public Integer getScoreHigh() {
        return scoreHigh;
    }

    public void setScoreHigh(Integer scoreHigh) {
        this.scoreHigh = scoreHigh;
    }

    /** Whether the score log is on. */
    public boolean isScoreLog() {
        return scoreLog;
    }

    public void setScoreLog(boolean scoreLog) {
        this.scoreLog = scoreLog;
    }

    /** The proof kinds as set, comma separated; null when blank (none). */
    public String getProofKinds() {
        return proofKinds;
    }

    public void setProofKinds(String proofKinds) {
        this.proofKinds = proofKinds;
    }

    /** The default grade as set; null when blank (A). */
    public String getDefaultGrade() {
        return defaultGrade;
    }

    public void setDefaultGrade(String defaultGrade) {
        this.defaultGrade = defaultGrade;
    }

    /** The grade a person added without one is given: the default grade, else A. */
    public String defaultGradeInForce() {
        return defaultGrade == null ? "A" : defaultGrade;
    }

    /** The proof kinds as a list; empty when there are none. */
    public List<String> proofKindList() {
        return proofKinds == null ? List.of() : Arrays.asList(proofKinds.split(","));
    }

    /** The month, 1 to 12, the sheet's fiscal year starts in. */
    public int getFiscalStartMonth() {
        return fiscalStartMonth;
    }

    public void setFiscalStartMonth(int fiscalStartMonth) {
        this.fiscalStartMonth = fiscalStartMonth;
    }

    /** The first day of the fiscal year named after a calendar year: the sheet's start month's first day in it. */
    public LocalDate fiscalYearStart(int year) {
        return LocalDate.of(year, fiscalStartMonth, 1);
    }

    /** The parent sheet's id; null when none. */
    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    /** The id of the sheet this one was cloned from; null when not cloned. */
    public String getClonedFrom() {
        return clonedFrom;
    }

    /** The date a new claim with no date of its own is given: the entry date while set, else the sheet's today. */
    public LocalDate defaultClaimDate() {
        return entryDate != null ? entryDate : today;
    }

    /** Whether a claim settled now is numbered within its period: the setting is a date on or before today. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** Give out the sheet's next number for a claim in no period: 1 for the first, then 2, and so on. */
    public int nextUnperiodClaimNumber() {
        return ++lastUnperiodClaimNumber;
    }

    /** The last settle batch number given out; 0 when there has been none. */
    public int getLastSettleBatch() {
        return lastSettleBatch;
    }

    /** Give out the sheet's next settle batch number: 1 for the first, then 2, and so on. */
    public int nextSettleBatch() {
        return ++lastSettleBatch;
    }

    /** The last claim number the sheet gave out; 0 before any claim is opened. */
    public int getLastClaimNumber() {
        return lastClaimNumber;
    }

    /** The year the fiscal year a date falls in is named after: the calendar year it starts in. */
    public int fiscalYearOf(LocalDate date) {
        return date.getMonthValue() >= fiscalStartMonth ? date.getYear() : date.getYear() - 1;
    }

    /** The last claim number the sheet gave out in a fiscal year; 0 before any. */
    public int getLastClaimNumberIn(int fiscalYear) {
        return lastFiscalClaimNumbers.getOrDefault(fiscalYear, 0);
    }

    /** Give out the next claim number in a fiscal year after the last given in it anywhere in the sheet's tree. */
    public int nextClaimNumberIn(int fiscalYear, int lastInTree) {
        int next = Math.max(getLastClaimNumberIn(fiscalYear), lastInTree) + 1;
        lastFiscalClaimNumbers.put(fiscalYear, next);
        return next;
    }

    /** The roles set for an action, comma separated; null when it keeps its standing rule. */
    public String getPermission(String action) {
        return permissions.get(action);
    }

    /** Set the roles that may take an action (null: the standing rule). */
    public void setPermission(String action, String roles) {
        if (roles == null) {
            permissions.remove(action);
        } else {
            permissions.put(action, roles);
        }
    }

    /** Whether a role may take an action: the roles set for it, else its standing rule. */
    public boolean permits(String action, String role) {
        String roles = permissions.getOrDefault(action, STANDING_PERMISSIONS.get(action));
        // A restricted user name sees less of the people list, but acts as a clerk.
        String as = Role.RESTRICTED.equals(role) ? Role.CLERK : role;
        return Arrays.asList(roles.split(",")).contains(as);
    }
}
