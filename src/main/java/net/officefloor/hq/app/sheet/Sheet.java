package net.officefloor.hq.app.sheet;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** A sheet: everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    /** The keys of the dated settings: each value is kept from its from-date. */
    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String FLOOR = "floor";
    public static final String TERMS = "terms";
    public static final String LATE_PCT = "late-pct";
    public static final String CAP = "cap";
    public static final String PROOF_THRESHOLD = "proof-threshold";
    public static final String NOTE_THRESHOLD = "note-threshold";
    public static final String REVIEW_THRESHOLD = "review-threshold";
    /** The rounding mode: half-up or half-even; none (null) leaves the policy date to decide. */
    public static final String ROUNDING = "rounding";
    public static final List<String> ROUNDING_MODES = List.of("half-up", "half-even");
    /** The kinds a proof can be: a proof added with none chosen is a receipt. */
    public static final List<String> PROOF_KINDS = List.of("receipt", "photo");
    /** The prefix of a grade's grace days key (grace-A, grace-B, grace-C). */
    public static final String GRACE = "grace";

    /** The actions the permission matrix decides, each with the roles that may take it when its entry is blank. */
    public static final Map<String, String> STANDING_PERMISSIONS = Map.of("open", "clerk,steward", "settle",
            "clerk,steward", "void", "clerk,steward", "reassign", "steward", "close", "clerk,steward", "reopen",
            "steward", "today", "clerk,steward,restricted");
    public static final List<String> PERMISSION_ACTIONS = List.of("open", "settle", "void", "reassign", "close",
            "reopen", "today");

    /** The sheet's starting values, in force before a key's first from-date: none for a key not listed. */
    private static final Map<String, String> STARTING = Map.of(FLOOR, "1.00", TERMS, "14", LATE_PCT, "0.00");

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** The sheet's own today: every date-dependent decision and default date uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The values of the dated settings (the clerk limit, the floor, the terms and each grade's, the late percent,
     *  the cap and the proof threshold), each from its from-date. */
    @OneToMany(mappedBy = "sheet", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @OrderBy("key ASC, fromDate ASC")
    private List<DatedSetting> dated = new ArrayList<>();

    /** The permission matrix: the roles (comma separated) that may take each action; an action left out keeps the
     *  standing rule. Not dated. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_permission", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "action")
    @Column(name = "roles", nullable = false)
    private Map<String, String> permissions = new HashMap<>();

    /** The policy date: a claim dated before it rounds half up, one on or after it half to even; null: the today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** From this date (on the sheet's today) a claim settled is numbered within its period; null: off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The default date of a new claim in place of the sheet's today; null: off. Not dated. */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** Days past an open period's to-date after which a change of today closes it; null: off. Not dated. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** Days at least which a claim's previous reminder lies before the sheet's today for a reminder to escalate it; null: off. Not dated. */
    @Column(name = "escalate_after")
    private Integer escalateAfter;

    /** Days after its review day a REVIEW claim may wait before it is late; null: none. Not dated. */
    @Column(name = "review_sla")
    private Integer reviewSla;

    /** Days after its pending day a PENDING claim may wait before it is late; null: none. Not dated. */
    @Column(name = "pending_sla")
    private Integer pendingSla;

    /** The most OPEN or PENDING claims a person may have; null: none. Not dated. */
    @Column(name = "max_open")
    private Integer maxOpen;

    /** The score at or above which a person is MID while both score thresholds are set; null: blank. Not dated. */
    @Column(name = "score_mid")
    private Integer scoreMid;

    /** The score at or above which a person is HIGH while both score thresholds are set; null: blank. Not dated. */
    @Column(name = "score_high")
    private Integer scoreHigh;

    /** Whether changing the sheet's today records each person's score on the day being left; false: blank. Not dated. */
    @Column(name = "score_log")
    private boolean scoreLog;

    /** The month (1 to 12) the sheet's fiscal year starts in. Not dated. */
    @Column(name = "fiscal_start_month", nullable = false)
    private int fiscalStartMonth = 1;

    /** The pot shared out over each balanced pool's members by their shares; null: none. Not dated. */
    @Column(name = "pot")
    private BigDecimal pot;

    /** The due-total above which a person's due-total crossing it is reported in an ALERT line; null: off. Not dated. */
    @Column(name = "alert_threshold")
    private BigDecimal alertThreshold;

    /** The id of the parent sheet whose rates a grade with none in force here falls back on; null: none. Not dated. */
    @Column(name = "parent")
    private String parent;

    /**
     * The id of the sheet this one was cloned from, whose rates a grade with none in force here nor on the parents falls
     * back on; null: not cloned. Not dated.
     */
    @Column(name = "source")
    private String source;

    /** The kinds of proof (comma separated) a claim above the proof threshold needs one of each of; null: none. Not dated. */
    @Column(name = "proof_kinds")
    private String proofKinds;

    /** The grade a person added without one takes (quick add, an import line with a blank grade); null: A. Not dated. */
    @Column(name = "default_grade")
    private String defaultGrade;

    /** The grades retired on the sheet (comma separated): none can be given to anyone from then on; null: none. Not dated. */
    @Column(name = "retired_grades")
    private String retiredGrades;

    /** The grades added to the sheet beyond A, B and C (comma separated), in the order they were added; null: none. Not dated. */
    @Column(name = "added_grades")
    private String addedGrades;

    /** Whether a steward archived the sheet: everything stays visible and every change is refused. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    /** The number of the sheet's last bulk settle batch: 0 while there has been none. Moved on only through the repository. */
    @Column(name = "last_settle_batch", nullable = false, insertable = false, updatable = false)
    private long lastSettleBatch;

    protected Sheet() {
    }

    public Sheet(String id, String createdBy, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.today = today;
    }

    /**
     * A copy of the sheet under another id: its today, dated settings, permission matrix and other settings; not
     * archived, with no settle batch yet; cloned from this sheet.
     */
    public Sheet copyTo(String id, String createdBy) {
        Sheet copy = new Sheet(id, createdBy, today);
        dated.forEach(d -> copy.dated.add(new DatedSetting(copy, d.getKey(), d.getFromDate(), d.getValue())));
        copy.permissions.putAll(permissions);
        copy.policyDate = policyDate;
        copy.periodNumberingFrom = periodNumberingFrom;
        copy.entryDate = entryDate;
        copy.graceDays = graceDays;
        copy.escalateAfter = escalateAfter;
        copy.reviewSla = reviewSla;
        copy.pendingSla = pendingSla;
        copy.maxOpen = maxOpen;
        copy.scoreMid = scoreMid;
        copy.scoreHigh = scoreHigh;
        copy.scoreLog = scoreLog;
        copy.fiscalStartMonth = fiscalStartMonth;
        copy.pot = pot;
        copy.alertThreshold = alertThreshold;
        copy.parent = parent;
        copy.source = this.id;
        copy.proofKinds = proofKinds;
        copy.defaultGrade = defaultGrade;
        copy.retiredGrades = retiredGrades;
        copy.addedGrades = addedGrades;
        return copy;
    }

    public String getId() {
        return id;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public long getLastSettleBatch() {
        return lastSettleBatch;
    }

    public LocalDate getToday() {
        return today;
    }

    public void setToday(LocalDate today) {
        this.today = today;
    }

    /** The key of a grade's own terms. */
    public static String gradeTermsKey(String grade) {
        return TERMS + "-" + grade;
    }

    /** The key of a grade's own proof threshold (proof-threshold-A, proof-threshold-B, proof-threshold-C). */
    public static String gradeProofThresholdKey(String grade) {
        return PROOF_THRESHOLD + "-" + grade;
    }

    /** The key of a grade's own clerk limit (clerk-limit-A, clerk-limit-B, clerk-limit-C). */
    public static String gradeClerkLimitKey(String grade) {
        return CLERK_LIMIT + "-" + grade;
    }

    /** The key of a grade's grace days. */
    public static String gradeGraceKey(String grade) {
        return GRACE + "-" + grade;
    }

    /**
     * Record a dated setting's value from the date (null: no limit or cap), replacing the value already recorded from
     * that date; the other values recorded are kept.
     */
    public void record(String key, LocalDate from, String value) {
        dated.stream().filter(d -> d.getKey().equals(key) && d.getFromDate().equals(from)).findFirst()
                .ifPresentOrElse(d -> d.setValue(value), () -> dated.add(new DatedSetting(this, key, from, value)));
    }

    /** Each dated setting's recorded values by key, from-date first to last. */
    public Map<String, List<DatedSetting>> datedSettings() {
        Map<String, List<DatedSetting>> byKey = new TreeMap<>();
        dated.stream().sorted(Comparator.comparing(DatedSetting::getFromDate))
                .forEach(d -> byKey.computeIfAbsent(d.getKey(), k -> new ArrayList<>()).add(d));
        return byKey;
    }

    /**
     * The value of a dated setting in force on the date: the one with the latest from-date on or before it, else the
     * sheet's starting value; empty when that is none.
     */
    public Optional<String> valueOn(String key, LocalDate date) {
        return dated.stream()
                .filter(d -> d.getKey().equals(key) && !d.getFromDate().isAfter(date))
                .max(Comparator.comparing(DatedSetting::getFromDate))
                .map(d -> Optional.ofNullable(d.getValue()))
                .orElseGet(() -> Optional.ofNullable(STARTING.get(key)));
    }

    private Optional<BigDecimal> decimalOn(String key, LocalDate date) {
        return valueOn(key, date).map(v -> new BigDecimal(v).setScale(2, RoundingMode.UNNECESSARY));
    }

    /** The highest due a clerk may settle, in force on the sheet's today; null when there is no limit. */
    public BigDecimal getClerkLimit() {
        return decimalOn(CLERK_LIMIT, today).orElse(null);
    }

    /**
     * The clerk limit for a person of the grade, in force on the sheet's today: the grade's own when one is set, else
     * the sheet's; null when there is none.
     */
    public BigDecimal clerkLimitFor(String grade) {
        return grade == null ? getClerkLimit()
                : decimalOn(gradeClerkLimitKey(grade), today).orElseGet(this::getClerkLimit);
    }

    /** The least a live due of a claim dated on the date can be. */
    public BigDecimal floorOn(LocalDate date) {
        return decimalOn(FLOOR, date).orElseThrow();
    }

    /** The most a person's due-total can be, in force on the sheet's today; null when there is no cap. */
    public BigDecimal getCap() {
        return decimalOn(CAP, today).orElse(null);
    }

    /**
     * The due above which a claim dated on the date cannot be settled or approved without a proof; null when there is
     * no threshold then.
     */
    public BigDecimal proofThresholdOn(LocalDate date) {
        return decimalOn(PROOF_THRESHOLD, date).orElse(null);
    }

    /**
     * The proof threshold for a claim dated on the date of a person of the grade (the one in force on the claim's
     * date): the grade's own in force on the date when one is set, else the sheet's; null when there is none then.
     */
    public BigDecimal proofThresholdFor(String grade, LocalDate date) {
        return grade == null ? proofThresholdOn(date)
                : decimalOn(gradeProofThresholdKey(grade), date).orElseGet(() -> proofThresholdOn(date));
    }

    /**
     * The due above which a claim dated on the date cannot be opened with an empty note; null when there is no
     * threshold then.
     */
    public BigDecimal noteThresholdOn(LocalDate date) {
        return decimalOn(NOTE_THRESHOLD, date).orElse(null);
    }

    /**
     * The due above which a claim dated on the date goes to REVIEW when opened instead of OPEN; null when there is no
     * threshold then.
     */
    public BigDecimal reviewThresholdOn(LocalDate date) {
        return decimalOn(REVIEW_THRESHOLD, date).orElse(null);
    }

    /** The days after its date a claim dated on the date of a grade without its own terms then is due by. */
    public int termsOn(LocalDate date) {
        return Integer.parseInt(valueOn(TERMS, date).orElseThrow());
    }

    /** The grade's own terms in force on the date; empty when it has none then (the sheet's terms apply). */
    public Optional<Integer> gradeTermsOn(String grade, LocalDate date) {
        return valueOn(gradeTermsKey(grade), date).map(Integer::parseInt);
    }

    /** The days after its date a claim dated on the date of a person of the grade is due by. */
    public int termsFor(String grade, LocalDate date) {
        return gradeTermsOn(grade, date).orElseGet(() -> termsOn(date));
    }

    /** The grade's grace days in force on the date; empty when it has none then. */
    public Optional<Integer> gradeGraceOn(String grade, LocalDate date) {
        return grade == null ? Optional.empty() : valueOn(gradeGraceKey(grade), date).map(Integer::parseInt);
    }

    /**
     * The day a claim dated on the date, due by the due-by, of a person of the grade (the one in force on the claim's
     * date) counts as due by for going overdue and for its late charge: the due-by plus the grade's grace days in force
     * on the claim's date, else the due-by.
     */
    public LocalDate overdueBy(LocalDate dueBy, String grade, LocalDate date) {
        return dueBy == null ? null : dueBy.plusDays(gradeGraceOn(grade, date).orElse(0));
    }

    /** The percent of its figure an overdue claim gains for each 30-day boundary past its due-by falling on the date. */
    public BigDecimal latePctOn(LocalDate date) {
        return decimalOn(LATE_PCT, date).orElseThrow();
    }

    /** The roles entered for the action, comma separated; null when blank (the standing rule applies). */
    public String permission(String action) {
        return permissions.get(action);
    }

    /** Enter the roles that may take the action; null or blank keeps the standing rule. */
    public void setPermission(String action, String roles) {
        if (roles == null || roles.isBlank()) {
            permissions.remove(action);
        } else {
            permissions.put(action, roles);
        }
    }

    /** Whether the role (clerk or steward) may take the action: as entered in the matrix, else the standing rule. */
    public boolean allows(String action, String role) {
        String roles = permissions.getOrDefault(action, STANDING_PERMISSIONS.get(action));
        return roles != null && Arrays.stream(roles.split(",")).map(String::trim).anyMatch(role::equals);
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

    /** The default date of a new claim: the entry date while it is set, else the sheet's today. */
    public LocalDate entryDateOrToday() {
        return entryDate != null ? entryDate : today;
    }

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    public Integer getEscalateAfter() {
        return escalateAfter;
    }

    public void setEscalateAfter(Integer escalateAfter) {
        this.escalateAfter = escalateAfter;
    }

    public Integer getReviewSla() {
        return reviewSla;
    }

    public void setReviewSla(Integer reviewSla) {
        this.reviewSla = reviewSla;
    }

    public Integer getPendingSla() {
        return pendingSla;
    }

    public void setPendingSla(Integer pendingSla) {
        this.pendingSla = pendingSla;
    }

    public Integer getMaxOpen() {
        return maxOpen;
    }

    public void setMaxOpen(Integer maxOpen) {
        this.maxOpen = maxOpen;
    }

    public Integer getScoreMid() {
        return scoreMid;
    }

    public void setScoreMid(Integer scoreMid) {
        this.scoreMid = scoreMid;
    }

    public Integer getScoreHigh() {
        return scoreHigh;
    }

    public void setScoreHigh(Integer scoreHigh) {
        this.scoreHigh = scoreHigh;
    }

    public boolean isScoreLog() {
        return scoreLog;
    }

    public void setScoreLog(boolean scoreLog) {
        this.scoreLog = scoreLog;
    }

    public int getFiscalStartMonth() {
        return fiscalStartMonth;
    }

    public void setFiscalStartMonth(int fiscalStartMonth) {
        this.fiscalStartMonth = fiscalStartMonth;
    }

    /** The name (FYyyyy) of the fiscal year the date falls in: the calendar year of that fiscal year's first day. */
    public String fiscalYearOf(LocalDate date) {
        return fiscalYearOf(date, fiscalStartMonth);
    }

    /** The name (FYyyyy) of the fiscal year the date falls in when fiscal years start in the month (1 to 12). */
    public static String fiscalYearOf(LocalDate date, int startMonth) {
        return "FY" + (date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1);
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

    public String getSource() {
        return source;
    }

    public String getProofKinds() {
        return proofKinds;
    }

    public void setProofKinds(String proofKinds) {
        this.proofKinds = proofKinds;
    }

    public String getDefaultGrade() {
        return defaultGrade;
    }

    public void setDefaultGrade(String defaultGrade) {
        this.defaultGrade = defaultGrade;
    }

    /** The grade a person added without one takes: the default grade while it is not retired, else A. */
    public String defaultGradeOrA() {
        return defaultGrade == null || isGradeRetired(defaultGrade) ? "A" : defaultGrade;
    }

    /** The grades every sheet has, before any added to it. */
    public static final List<String> STANDING_GRADES = List.of("A", "B", "C");

    /** The sheet's grades: A, B and C, then those added to it in the order they were added. */
    public List<String> grades() {
        List<String> grades = new ArrayList<>(STANDING_GRADES);
        if (addedGrades != null) {
            grades.addAll(Arrays.asList(addedGrades.split(",")));
        }
        return grades;
    }

    public boolean hasGrade(String grade) {
        return grades().contains(grade);
    }

    /** Add the grade to the sheet's grades. */
    public void addGrade(String grade) {
        addedGrades = addedGrades == null ? grade : addedGrades + "," + grade;
    }

    /** The grades retired on the sheet, in the order they were retired. */
    public List<String> retiredGrades() {
        return retiredGrades == null ? List.of() : Arrays.stream(retiredGrades.split(",")).toList();
    }

    public boolean isGradeRetired(String grade) {
        return retiredGrades().contains(grade);
    }

    /** Retire the grade: it cannot be given to anyone from then on. */
    public void retireGrade(String grade) {
        retiredGrades = retiredGrades == null ? grade : retiredGrades + "," + grade;
    }

    /** The kinds of proof a claim above the proof threshold needs one of each of; empty while none are set. */
    public List<String> proofKindsNeeded() {
        return proofKinds == null ? List.of() : Arrays.stream(proofKinds.split(",")).map(String::trim).toList();
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    /** Whether a claim settled now is numbered within its period: the setting is a date and today is on or after it. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** The rounding mode (half-up or half-even) in force on the date; empty when there is none then. */
    public Optional<String> roundingModeOn(LocalDate date) {
        return valueOn(ROUNDING, date);
    }

    /**
     * How a claim dated {@code date} rounding by {@code roundingDate} rounds its live figures: by the rounding mode in
     * force on its date when there is one, else half up when the rounding date is before the policy date (the sheet's
     * today when it has none), half to even on or after it.
     */
    public RoundingMode rounding(LocalDate date, LocalDate roundingDate) {
        return rounding(date, roundingDate, today);
    }

    /** How a claim rounds, with {@code asOf} standing in for the sheet's today. */
    public RoundingMode rounding(LocalDate date, LocalDate roundingDate, LocalDate asOf) {
        return roundingModeOn(date)
                .map(mode -> mode.equals("half-up") ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN)
                .orElseGet(() -> roundingDate.isBefore(policyDate != null ? policyDate : asOf) ? RoundingMode.HALF_UP
                        : RoundingMode.HALF_EVEN);
    }

    /**
     * The rate step: the amount at the percent, to two decimals rounded as a claim dated {@code date} rounding by the
     * rounding date does.
     */
    public BigDecimal rateStep(BigDecimal amount, BigDecimal pct, LocalDate date, LocalDate roundingDate) {
        return rateStep(amount, pct, date, roundingDate, today);
    }

    /** The rate step with {@code asOf} standing in for the sheet's today. */
    public BigDecimal rateStep(BigDecimal amount, BigDecimal pct, LocalDate date, LocalDate roundingDate,
            LocalDate asOf) {
        return amount.multiply(pct).movePointLeft(2).setScale(2, rounding(date, roundingDate, asOf));
    }

    /**
     * The late charge on a figure of an OPEN claim dated {@code date}: for every full 30 days the sheet's today is past
     * the claim's due-by, the late percent in force on that boundary (the due-by plus 30, 60, 90 ... days) of the
     * figure plus the charges of the boundaries before it (compounding), each rounded as the claim's date has it,
     * added up; zero when not overdue, null (n/a) when the figure is.
     */
    public BigDecimal lateCharge(BigDecimal figure, LocalDate dueBy, LocalDate date) {
        return lateCharge(figure, dueBy, date, today);
    }

    /** The late charge with {@code asOf} standing in for the sheet's today. */
    public BigDecimal lateCharge(BigDecimal figure, LocalDate dueBy, LocalDate date, LocalDate asOf) {
        if (figure == null) {
            return null;
        }
        long overdueDays = ChronoUnit.DAYS.between(dueBy, asOf);
        long periods = overdueDays <= 0 ? 0 : overdueDays / 30;
        BigDecimal charge = BigDecimal.ZERO.setScale(2);
        for (long boundary = 1; boundary <= periods; boundary++) {
            charge = charge.add(figure.add(charge).multiply(latePctOn(dueBy.plusDays(30 * boundary))).movePointLeft(2)
                    .setScale(2, rounding(date, date, asOf)));
        }
        return charge;
    }

    /** The figure with its late charge added on top when the claim is OPEN; null (n/a) stays null. */
    public BigDecimal withLateCharge(BigDecimal figure, boolean open, LocalDate dueBy, LocalDate date) {
        return withLateCharge(figure, open, dueBy, date, today);
    }

    /** The figure with its late charge, with {@code asOf} standing in for the sheet's today. */
    public BigDecimal withLateCharge(BigDecimal figure, boolean open, LocalDate dueBy, LocalDate date,
            LocalDate asOf) {
        return figure == null || !open ? figure : figure.add(lateCharge(figure, dueBy, date, asOf));
    }

    /** A due-total held down to the cap in force on the sheet's today; null (n/a), or no cap, leaves it as it is. */
    public BigDecimal capped(BigDecimal dueTotal) {
        return capped(dueTotal, today);
    }

    /** A due-total held down to the cap in force on {@code asOf}, standing in for the sheet's today. */
    public BigDecimal capped(BigDecimal dueTotal, LocalDate asOf) {
        return capped(dueTotal, BigDecimal.ZERO, asOf);
    }

    /**
     * A due-total held down to the cap in force on the sheet's today less the person's cut total (not below 0.00);
     * null (n/a), or no cap, leaves it as it is.
     */
    public BigDecimal capped(BigDecimal dueTotal, BigDecimal cutTotal) {
        return capped(dueTotal, cutTotal, today);
    }

    /** A due-total held down to the cap in force on {@code asOf} less the cut total (not below 0.00). */
    public BigDecimal capped(BigDecimal dueTotal, BigDecimal cutTotal, LocalDate asOf) {
        BigDecimal cap = decimalOn(CAP, asOf).orElse(null);
        if (dueTotal == null || cap == null) {
            return dueTotal;
        }
        BigDecimal room = cap.subtract(cutTotal).max(BigDecimal.ZERO).setScale(Math.max(2, dueTotal.scale()));
        return dueTotal.min(room);
    }

    /** A live due of a claim dated on the date raised to the floor in force on it; null (n/a) stays null. */
    public BigDecimal floored(BigDecimal due, LocalDate date) {
        return due == null ? null : due.max(floorOn(date));
    }

    /**
     * The live due on a rate figure of a claim dated on the date: the figure raised to the floor in force on that date,
     * with the late charge worked out on that floored figure added on top when the claim is OPEN; null (n/a) stays null.
     */
    public BigDecimal liveDue(BigDecimal figure, boolean open, LocalDate dueBy, LocalDate date) {
        return liveDue(figure, open, dueBy, date, today);
    }

    /** The live due with {@code asOf} standing in for the sheet's today. */
    public BigDecimal liveDue(BigDecimal figure, boolean open, LocalDate dueBy, LocalDate date, LocalDate asOf) {
        return withLateCharge(floored(figure, date), open, dueBy, date, asOf);
    }
}
