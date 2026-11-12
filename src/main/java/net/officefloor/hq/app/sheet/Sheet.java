package net.officefloor.hq.app.sheet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.officefloor.hq.app.history.History;

/** A sheet, chosen by its id. Everything else in the app is kept per sheet. */
@Entity
@EntityListeners(History.class)
@Table(name = "sheet")
public class Sheet {

    public static final String FLOOR = "floor";
    public static final String LATE_PCT = "late-pct";
    public static final String TERMS = "terms";
    public static final String GRACE = "grace";
    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String CAP = "cap";
    public static final String PROOF_THRESHOLD = "proof-threshold";
    public static final String NOTE_THRESHOLD = "note-threshold";
    /** The actions the permission matrix covers, and the roles that may take each by the standing rule. */
    public static final Map<String, List<String>> STANDING_PERMISSIONS = Map.of(
            "open", List.of("clerk", "steward"), "settle", List.of("clerk", "steward"),
            "void", List.of("clerk", "steward"), "close", List.of("clerk", "steward"),
            "reassign", List.of("steward"), "reopen", List.of("steward"));
    private static final BigDecimal START_FLOOR = new BigDecimal("1.00");
    private static final BigDecimal START_LATE_PCT = new BigDecimal("0.00");
    private static final int START_TERMS = 14;

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: every date decision and default date in the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last number given to a claim opened on the sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The settings that carry a figure, each value from a from-date: the floor, the late percent, the terms,
     *  each grade's terms (terms-<grade>), each grade's grace days (grace-<grade>), the clerk limit, the cap, the proof threshold and the note threshold. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting", joinColumns = @JoinColumn(name = "sheet_id"))
    private List<SheetSetting> settings = new ArrayList<>();

    /** The permission matrix: for an action, the roles that may take it, comma separated; an action with none
     *  keeps the standing rule. Not dated. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_permission", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "action")
    @Column(name = "roles", nullable = false)
    private Map<String, String> permissions = new HashMap<>();

    /** The policy date: a claim dated before it rounds half up, on or after it half to even; none means
     *  the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** Period numbering from this date: while the sheet's today is on or after it, a claim opened is numbered
     *  within its period; none means off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The grace days: when the sheet's today changes, each open period whose to-date plus these days is before
     *  the new today is closed; none means off. Not dated. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** The entry date: while set, the default date of a new claim in place of the sheet's today; none means
     *  off. Not dated. */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** Whether the sheet is archived: everything stays visible and every change is refused. */
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

    /** Every value recorded for a setting that carries a figure, by key, then from-date. */
    public List<SheetSetting> getSettings() {
        return settings.stream()
                .sorted(Comparator.comparing(SheetSetting::getKey).thenComparing(SheetSetting::getFromDate))
                .toList();
    }

    /** Record a setting's value from a date, keeping its other values; one already from that date is
     *  replaced. None is a value too (no limit, no cap, a grade using the sheet's terms). */
    public void setSetting(String key, LocalDate from, String value) {
        settings.removeIf(s -> s.getKey().equals(key) && s.getFromDate().equals(from));
        settings.add(new SheetSetting(key, from, value));
    }

    /** The value of a setting in force on a date: the one with the latest from-date on or before it; empty
     *  when none is recorded by then, null inside when the one in force is none. */
    private Optional<SheetSetting> inForce(String key, LocalDate date) {
        return settings.stream()
                .filter(s -> s.getKey().equals(key) && !s.getFromDate().isAfter(date))
                .max(Comparator.comparing(SheetSetting::getFromDate));
    }

    private BigDecimal decimalOn(String key, LocalDate date, BigDecimal start) {
        return inForce(key, date).map(s -> s.getValue() == null ? null : new BigDecimal(s.getValue())).orElse(start);
    }

    /** The largest due a clerk may settle, in force on the sheet's today; none when there is no limit. */
    public BigDecimal getClerkLimit() {
        return decimalOn(CLERK_LIMIT, today, null);
    }

    /** The least a claim's live due can be on a date; 1.00 before every from-date. */
    public BigDecimal floorOn(LocalDate date) {
        return decimalOn(FLOOR, date, START_FLOOR);
    }

    /** The floor in force on the sheet's today. */
    public BigDecimal getFloor() {
        return floorOn(today);
    }

    /** The most a person's due-total can be, in force on the sheet's today; none when there is no cap. */
    public BigDecimal getCap() {
        return decimalOn(CAP, today, null);
    }

    /** The proof threshold in force on a date: a claim dated then whose due is above it needs a proof to be
     *  settled or approved; none when there is no threshold. */
    public BigDecimal proofThresholdOn(LocalDate date) {
        return decimalOn(PROOF_THRESHOLD, date, null);
    }

    /** The proof threshold in force on the sheet's today; none when there is no threshold. */
    public BigDecimal getProofThreshold() {
        return proofThresholdOn(today);
    }

    /** The note threshold in force on a date: a claim dated then whose due is above it cannot be opened with
     *  an empty note; none when there is no threshold. */
    public BigDecimal noteThresholdOn(LocalDate date) {
        return decimalOn(NOTE_THRESHOLD, date, null);
    }

    /** The note threshold in force on the sheet's today; none when there is no threshold. */
    public BigDecimal getNoteThreshold() {
        return noteThresholdOn(today);
    }

    /** A person's due-total as it counts: at most the cap; none (n/a) stays none. */
    public BigDecimal capped(BigDecimal dueTotal) {
        BigDecimal cap = getCap();
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
    }

    /** The sheet's terms on a date; 14 before every from-date. */
    public int termsOn(LocalDate date) {
        return inForce(TERMS, date).map(s -> Integer.valueOf(s.getValue())).orElse(START_TERMS);
    }

    /** The sheet's terms in force on its today. */
    public int getTerms() {
        return termsOn(today);
    }

    /** A grade's own terms on a date; none when it uses the sheet's terms then. */
    public Integer gradeTermsOn(String grade, LocalDate date) {
        return inForce(TERMS + "-" + grade, date).map(SheetSetting::getValue).map(Integer::valueOf).orElse(null);
    }

    /** The grades with their own terms in force on the sheet's today, and their terms. */
    public Map<String, Integer> getGradeTerms() {
        Map<String, Integer> out = new TreeMap<>();
        settings.stream().map(SheetSetting::getKey).filter(k -> k.startsWith(TERMS + "-")).distinct()
                .forEach(k -> {
                    String grade = k.substring(TERMS.length() + 1);
                    Integer own = gradeTermsOn(grade, today);
                    if (own != null) {
                        out.put(grade, own);
                    }
                });
        return out;
    }

    /** The terms of a grade on a date: its own, else the sheet's (the sheet's for no grade). */
    public int termsFor(String grade, LocalDate date) {
        Integer own = grade == null ? null : gradeTermsOn(grade, date);
        return own != null ? own : termsOn(date);
    }

    /** A grade's grace days on a date; none when it has none then (and for no grade). */
    public Integer gradeGraceOn(String grade, LocalDate date) {
        return grade == null ? null
                : inForce(GRACE + "-" + grade, date).map(SheetSetting::getValue).map(Integer::valueOf).orElse(null);
    }

    /** The date a claim's lateness runs from: its due-by plus the grace days in force on its date of its
     *  person's grade in force then (none: the due-by). It is overdue once this is before the sheet's today,
     *  and its late charge's 30-day boundaries count from it. */
    public LocalDate lateFrom(LocalDate dueBy, String grade, LocalDate date) {
        Integer grace = gradeGraceOn(grade, date);
        return grace == null ? dueBy : dueBy.plusDays(grace);
    }

    /** The late percent on a date; 0.00 before every from-date. */
    public BigDecimal latePctOn(LocalDate date) {
        return decimalOn(LATE_PCT, date, START_LATE_PCT);
    }

    /** The late percent in force on the sheet's today. */
    public BigDecimal getLatePct() {
        return latePctOn(today);
    }

    /** The roles typed for each action in the permission matrix; an action not here keeps the standing rule. */
    public Map<String, String> getPermissions() {
        return new TreeMap<>(permissions);
    }

    /** Set the roles that may take an action, comma separated; blank keeps the standing rule. */
    public void setPermission(String action, String roles) {
        if (roles == null || roles.isBlank()) {
            permissions.remove(action);
        } else {
            permissions.put(action, roles);
        }
    }

    /** Whether a role may take an action: by the permission matrix, else by the standing rule. */
    public boolean allows(String action, String role) {
        String typed = permissions.get(action);
        List<String> allowed = typed == null ? STANDING_PERMISSIONS.getOrDefault(action, List.of())
                : Arrays.stream(typed.split(",")).map(String::trim).toList();
        return allowed.contains(role);
    }

    public boolean isArchived() {
        return archived;
    }

    public void archive() {
        this.archived = true;
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

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    /** Whether a claim opened now is numbered within its period: the setting is a date and today is on or
     *  after it. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** How a claim dated on a date rounds its figures: half up before the policy date (the sheet's today
     *  when it has none), half to even on or after it. */
    public RoundingMode rounding(LocalDate date) {
        LocalDate policy = policyDate == null ? today : policyDate;
        return date.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** The late charge on an overdue claim's figure: for each full 30 days past the date its lateness runs
     *  from (its due-by plus any grace, see lateFrom) on the sheet's today, the late percent in force on that
     *  boundary date (that date plus 30, 60, 90 ... days) of the
     *  figure, rounded to cents as its date rounds, the charges added up (a boundary at 0.00 adds nothing);
     *  0.00 for no figure (n/a). Only an OPEN or PENDING claim is overdue, so the caller asks only for those. */
    public BigDecimal lateCharge(BigDecimal figure, LocalDate lateFrom, LocalDate date) {
        BigDecimal charge = BigDecimal.ZERO.setScale(2);
        if (figure == null) {
            return charge;
        }
        long boundaries = ChronoUnit.DAYS.between(lateFrom, today) / 30;
        RoundingMode rounding = rounding(date);
        for (long k = 1; k <= boundaries; k++) {
            charge = charge.add(figure.multiply(latePctOn(lateFrom.plusDays(30 * k))).movePointLeft(2)
                    .setScale(2, rounding));
        }
        return charge;
    }

    /** A live due of a claim dated on a date as it counts: at least the floor in force then; none (n/a)
     *  stays none. */
    public BigDecimal floored(BigDecimal due, LocalDate date) {
        return due == null ? null : due.max(floorOn(date));
    }

    /** Give out the sheet's next claim number. */
    public String nextClaimNumber() {
        return String.valueOf(++lastClaimNumber);
    }
}
