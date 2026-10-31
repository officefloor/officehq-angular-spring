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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** A sheet: the unit everything else in the app is kept under, chosen by its id. */
@Entity
@Table(name = "sheet")
public class Sheet {

    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String FLOOR = "floor";
    public static final String TERMS = "terms";
    public static final String LATE_PCT = "late-pct";
    public static final String GRACE = "grace";
    public static final String CAP = "cap";
    public static final String PROOF_THRESHOLD = "proof-threshold";
    public static final String NOTE_THRESHOLD = "note-threshold";

    /** The values a sheet starts with, in force before every from-date of their key. */
    static final BigDecimal START_FLOOR = new BigDecimal("1.00");
    static final int START_TERMS = 14;
    static final BigDecimal START_LATE_PCT = new BigDecimal("0.00");

    private static final List<String> GRADES = List.of("A", "B", "C");

    /** The actions the permission matrix covers, and the roles that may take each while its entry is blank. */
    public static final Map<String, String> STANDING_PERMISSIONS = Map.of(
            "open", "clerk,steward", "settle", "clerk,steward", "void", "clerk,steward",
            "reassign", "steward", "close", "clerk,steward", "reopen", "steward");

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: used for every date-dependent decision and default instead of the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last number given to a claim opened on this sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The number of the last settle batch on this sheet (a bulk settle that settled at least one claim); 0 before
     *  the first. */
    @Column(name = "last_settle_batch", nullable = false)
    private int lastSettleBatch;

    /** The dated values of the settings that carry a figure (keys: clerk-limit, floor, terms, terms-A/B/C, late-pct,
     *  grace-A/B/C, cap, proof-threshold, proof-threshold-A/B/C, note-threshold): each in force from its from-date until the next one's of the same key. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting", joinColumns = @JoinColumn(name = "sheet_id"))
    private List<DatedSetting> settings = new ArrayList<>();

    /** The permission matrix: for an action, the roles that may take it (comma separated); an action missing keeps
     *  its standing rule. Not dated. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_permission", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "action")
    @Column(name = "roles", nullable = false)
    private Map<String, String> permissions = new TreeMap<>();

    /** The sheet's policy date: a claim dated before it rounds half up, one on or after it half to even; null means
     *  the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The sheet's period numbering from: while its today is on or after this, a claim opened is numbered within
     *  its period; null means off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The sheet's entry date: while set, a new claim given no date takes it in place of the sheet's today; null
     *  means off. Not dated. */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** The sheet's grace days: when its today changes, every open period whose to-date plus these is before the
     *  new today is closed; null means off. Not dated. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** The sheet's pot (two decimals), cut among the members of each balanced pool that is a member of none; null means none. Not dated. */
    @Column(name = "pot", precision = 14, scale = 2)
    private BigDecimal pot;

    /** The id of the sheet's parent sheet, whose rates (then its parent's, up to three levels up) stand in for a
     *  grade with no rate in force on a date on this sheet; null means none. Not dated. */
    @Column(name = "parent_id")
    private String parentId;

    /** Whether the sheet is archived: everything on it stays visible and every change to it is refused. */
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

    /** A copy of the sheet under another id, unsaved: its today, every setting (dated or not), its permission
     *  matrix, parent and last settle batch; its claim numbering starts afresh, and it is not archived. */
    public Sheet copyTo(String id, String createdBy, Instant createdAt) {
        Sheet copy = new Sheet(id, createdBy, createdAt, today);
        settings.forEach(s -> copy.settings.add(new DatedSetting(s.getKey(), s.getFrom(), s.getValue())));
        copy.permissions.putAll(permissions);
        copy.policyDate = policyDate;
        copy.periodNumberingFrom = periodNumberingFrom;
        copy.entryDate = entryDate;
        copy.graceDays = graceDays;
        copy.pot = pot;
        copy.parentId = parentId;
        copy.lastSettleBatch = lastSettleBatch;
        return copy;
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

    /** Every dated value recorded, by key and then from-date (none first). */
    public List<DatedSetting> getSettings() {
        return settings.stream()
                .sorted(Comparator.comparing(DatedSetting::getKey)
                        .thenComparing(DatedSetting::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** Record a setting's value from a date, replacing the one it had from that same date. */
    public void record(String key, LocalDate from, String value) {
        settings.removeIf(s -> s.getKey().equals(key) && Objects.equals(s.getFrom(), from));
        settings.add(new DatedSetting(key, from, value));
    }

    /** The value of a setting recorded in force on a day: the one with the latest from-date on or before it;
     *  empty when none is (before every from-date). */
    private Optional<DatedSetting> recordOn(String key, LocalDate date) {
        return settings.stream()
                .filter(s -> s.getKey().equals(key) && s.startedBy(date))
                .max(Comparator.comparing(DatedSetting::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /** A setting's value in force on a day; null when none is recorded by then or it is none. */
    private String valueOn(String key, LocalDate date) {
        return recordOn(key, date).map(DatedSetting::getValue).orElse(null);
    }

    private BigDecimal amountOn(String key, LocalDate date, BigDecimal start) {
        String value = valueOn(key, date);
        return value == null ? start : new BigDecimal(value).setScale(2);
    }

    /** A clerk can settle a claim only when its due is at or below this, in force on the sheet's today; null means
     *  no limit. */
    public BigDecimal getClerkLimit() {
        return amountOn(CLERK_LIMIT, today, null);
    }

    /** A claim's live due is at least the floor in force on its date (two decimals). */
    public BigDecimal floorOn(LocalDate date) {
        return amountOn(FLOOR, date, START_FLOOR);
    }

    /** The floor in force on the sheet's today. */
    public BigDecimal getFloor() {
        return floorOn(today);
    }

    /** The sheet's terms in business days in force on a day: a claim dated then is due by the terms-th business day after its date. */
    public int termsOn(LocalDate date) {
        String value = valueOn(TERMS, date);
        return value == null ? START_TERMS : Integer.parseInt(value);
    }

    /** The sheet's terms in force on its today. */
    public int getTerms() {
        return termsOn(today);
    }

    /** The terms set per grade in force on the sheet's today, by grade; a grade without its own is missing. */
    public Map<String, Integer> getGradeTerms() {
        Map<String, Integer> byGrade = new TreeMap<>();
        for (String grade : GRADES) {
            String value = valueOn(TERMS + "-" + grade, today);
            if (value != null) {
                byGrade.put(grade, Integer.parseInt(value));
            }
        }
        return byGrade;
    }

    /** The terms in days for a grade in force on a day: its own then, else the sheet's then. */
    public int termsFor(String grade, LocalDate date) {
        String value = valueOn(TERMS + "-" + grade, date);
        return value == null ? termsOn(date) : Integer.parseInt(value);
    }

    /** The grace days set per grade in force on the sheet's today, by grade; a grade without any is missing. */
    public Map<String, Integer> getGradeGrace() {
        Map<String, Integer> byGrade = new TreeMap<>();
        for (String grade : GRADES) {
            String value = valueOn(GRACE + "-" + grade, today);
            if (value != null) {
                byGrade.put(grade, Integer.parseInt(value));
            }
        }
        return byGrade;
    }

    /** The grace days of a grade in force on a day: a claim dated then whose person is of that grade then is overdue
     *  only once its due-by plus these is before the sheet's today; 0 when none is set. */
    public int graceFor(String grade, LocalDate date) {
        String value = valueOn(GRACE + "-" + grade, date);
        return value == null ? 0 : Integer.parseInt(value);
    }

    /** The sheet's late percent in force on a day (two decimals): an overdue claim gains this percent of its figure,
     *  plus the charges of the boundaries before it, for each full 30 days it is overdue whose boundary (its due-by
     *  plus 30, 60, 90 ... days) falls then. */
    public BigDecimal latePctOn(LocalDate date) {
        return amountOn(LATE_PCT, date, START_LATE_PCT);
    }

    /** The late percent in force on the sheet's today. */
    public BigDecimal getLatePct() {
        return latePctOn(today);
    }

    /** The permission matrix entries set, by action; an action missing is blank (its standing rule). */
    public Map<String, String> getPermissions() {
        return new TreeMap<>(permissions);
    }

    /** Set the roles that may take an action (comma separated); null or blank keeps its standing rule. */
    public void setPermission(String action, String roles) {
        if (roles == null || roles.isBlank()) {
            permissions.remove(action);
        } else {
            permissions.put(action, roles);
        }
    }

    /** Whether a role may take an action: by the matrix entry, else the standing rule. */
    public boolean allows(String action, String role) {
        String roles = permissions.getOrDefault(action, STANDING_PERMISSIONS.get(action));
        return Arrays.stream(roles.split(",")).map(String::trim).anyMatch(role::equals);
    }

    public LocalDate getPolicyDate() {
        return policyDate;
    }

    public void setPolicyDate(LocalDate policyDate) {
        this.policyDate = policyDate;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    /** The date a new claim given no date takes: the entry date while set, else the sheet's today. */
    public LocalDate defaultClaimDate() {
        return entryDate != null ? entryDate : today;
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

    public BigDecimal getPot() {
        return pot;
    }

    public void setPot(BigDecimal pot) {
        this.pot = pot;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public boolean isArchived() {
        return archived;
    }

    /** Archive the sheet; it is never unarchived. */
    public void archive() {
        this.archived = true;
    }

    /** Whether a period ending on this day is closed by itself at the sheet's today: grace days are set and the day
     *  plus them is before today. */
    public boolean pastGrace(LocalDate toDate) {
        return graceDays != null && toDate.plusDays(graceDays).isBefore(today);
    }

    /** A claim dated on a day whose due is above the proof threshold in force then (two decimals) cannot be settled
     *  or approved while it has no proofs; null means none. */
    public BigDecimal proofThresholdOn(LocalDate date) {
        return amountOn(PROOF_THRESHOLD, date, null);
    }

    /** The proof thresholds set per grade in force on the sheet's today, by grade; a grade without its own is missing. */
    public Map<String, String> getGradeProofThresholds() {
        Map<String, String> byGrade = new TreeMap<>();
        for (String grade : GRADES) {
            BigDecimal value = amountOn(PROOF_THRESHOLD + "-" + grade, today, null);
            if (value != null) {
                byGrade.put(grade, value.toPlainString());
            }
        }
        return byGrade;
    }

    /** The proof threshold for a grade in force on a day: its own then when one is set, else the sheet's then;
     *  null means none. */
    public BigDecimal proofThresholdFor(String grade, LocalDate date) {
        BigDecimal own = amountOn(PROOF_THRESHOLD + "-" + grade, date, null);
        return own != null ? own : proofThresholdOn(date);
    }

    /** The proof threshold in force on the sheet's today; null means none. */
    public BigDecimal getProofThreshold() {
        return proofThresholdOn(today);
    }

    /** A claim dated on a day whose due is above the note threshold in force then (two decimals) cannot be opened
     *  while its note is empty; null means none. */
    public BigDecimal noteThresholdOn(LocalDate date) {
        return amountOn(NOTE_THRESHOLD, date, null);
    }

    /** The note threshold in force on the sheet's today; null means none. */
    public BigDecimal getNoteThreshold() {
        return noteThresholdOn(today);
    }

    /** A person's due-total is at most this (two decimals), in force on the sheet's today; null means none. */
    public BigDecimal getCap() {
        return amountOn(CAP, today, null);
    }

    /** A person's due-total as it counts: the sum of their figures, at most the cap when there is one. */
    public BigDecimal capped(BigDecimal sum) {
        BigDecimal cap = getCap();
        return cap == null || sum.compareTo(cap) <= 0 ? sum : cap;
    }

    /** Whether a claim opened now is numbered within its period: the period numbering from is set and the
     *  sheet's today is on or after it. */
    public boolean numbersPerPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** How a claim's figures round when the claim (or, for a correction, the claim it corrects) is dated on this
     *  day: half up before the policy date (the sheet's today when it has none), half to even on or after it. */
    public RoundingMode roundingFor(LocalDate date) {
        LocalDate policy = policyDate != null ? policyDate : today;
        return date.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** The last number given to a claim opened on this sheet; 0 before the first. */
    public int getLastClaimNumber() {
        return lastClaimNumber;
    }

    /** Take the next claim number on this sheet after the last one given anywhere in its tree (this sheet's own
     *  last included); it is never given out again. */
    public int nextClaimNumberAfter(int lastInTree) {
        lastClaimNumber = Math.max(lastClaimNumber, lastInTree) + 1;
        return lastClaimNumber;
    }

    /** The number of the last settle batch on this sheet; 0 when there has been none. */
    public int getLastSettleBatch() {
        return lastSettleBatch;
    }

    public void setLastSettleBatch(int lastSettleBatch) {
        this.lastSettleBatch = lastSettleBatch;
    }

    /** Take the next settle batch number on this sheet. */
    public int nextSettleBatch() {
        return ++lastSettleBatch;
    }
}
