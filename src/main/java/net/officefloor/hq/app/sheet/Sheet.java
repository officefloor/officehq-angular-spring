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
    public static final String GRACE = "grace";

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

    /** The last claim number the sheet gave out; 0 before any claim is opened. */
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

    /** Archived: everything on the sheet stays visible but nothing on it may change. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    /** The last X-number given to a claim in no period while period numbering is on; 0 before any. */
    @Column(name = "last_unperiod_claim_number", nullable = false)
    private int lastUnperiodClaimNumber;

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

    /** The cap in force on the sheet's today, with two decimals; null when there is none. */
    public BigDecimal getCap() {
        return decimal(valueOn(CAP, today, plain(cap)));
    }

    /** A sum of figures held to the cap in force today: the cap when the sum is above it; null (n/a) stays null. */
    public BigDecimal capped(BigDecimal sum) {
        BigDecimal cap = getCap();
        return sum != null && cap != null && sum.compareTo(cap) > 0 ? cap : sum;
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
            default -> throw new IllegalArgumentException("No such grade: " + grade);
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
        then.archived = archived;
        then.dated = new ArrayList<>(datedThen);
        then.permissions = new HashMap<>(permissions);
        return then;
    }

    /** The floor's values (see {@link #history}). */
    public List<DatedSetting> floorHistory() {
        return history(FLOOR, plain(floor));
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
     * How the live figures of a claim dated on a date round: half up when the date is before the
     * policy date (the sheet's today when none is set), else half to even.
     */
    public RoundingMode roundingOn(LocalDate date) {
        LocalDate policy = policyDate == null ? today : policyDate;
        return date.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
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

    /** The date a new claim with no date of its own is given: the entry date while set, else the sheet's today. */
    public LocalDate defaultClaimDate() {
        return entryDate != null ? entryDate : today;
    }

    /** Whether a claim opened now is numbered within its period: the setting is a date on or before today. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** Give out the sheet's next number for a claim in no period: 1 for the first, then 2, and so on. */
    public int nextUnperiodClaimNumber() {
        return ++lastUnperiodClaimNumber;
    }

    /** Give out the sheet's next claim number: 1 for the first, then 2, and so on. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
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
        return Arrays.asList(roles.split(",")).contains(role);
    }
}
