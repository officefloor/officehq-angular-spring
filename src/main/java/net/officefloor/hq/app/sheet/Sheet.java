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

    /** The actions the permission matrix decides, each with the roles that may take it when its entry is blank. */
    public static final Map<String, String> STANDING_PERMISSIONS = Map.of("open", "clerk,steward", "settle",
            "clerk,steward", "void", "clerk,steward", "reassign", "steward", "close", "clerk,steward", "reopen",
            "steward");
    public static final List<String> PERMISSION_ACTIONS = List.of("open", "settle", "void", "reassign", "close",
            "reopen");

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

    /** From this date (on the sheet's today) a claim opened is numbered within its period; null: off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** Days past an open period's to-date after which a change of today closes it; null: off. Not dated. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** Whether a steward archived the sheet: everything stays visible and every change is refused. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

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

    /** The key of a grade's own terms. */
    public static String gradeTermsKey(String grade) {
        return TERMS + "-" + grade;
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

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    /** Whether a claim opened now is numbered within its period: the setting is a date and today is on or after it. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /**
     * How a claim rounding by {@code date} rounds its live figures: half up when dated before the policy date (the
     * sheet's today when it has none), half to even on or after it.
     */
    public RoundingMode rounding(LocalDate date) {
        return rounding(date, today);
    }

    /** How a claim rounding by {@code date} rounds, with {@code asOf} standing in for the sheet's today. */
    public RoundingMode rounding(LocalDate date, LocalDate asOf) {
        return date.isBefore(policyDate != null ? policyDate : asOf) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** The rate step: the amount at the percent, to two decimals rounded as a claim rounding by the date does. */
    public BigDecimal rateStep(BigDecimal amount, BigDecimal pct, LocalDate roundingDate) {
        return rateStep(amount, pct, roundingDate, today);
    }

    /** The rate step with {@code asOf} standing in for the sheet's today. */
    public BigDecimal rateStep(BigDecimal amount, BigDecimal pct, LocalDate roundingDate, LocalDate asOf) {
        return amount.multiply(pct).movePointLeft(2).setScale(2, rounding(roundingDate, asOf));
    }

    /**
     * The late charge on a figure of an OPEN claim dated {@code date}: for every full 30 days the sheet's today is past
     * the claim's due-by, the late percent in force on that boundary (the due-by plus 30, 60, 90 ... days) of the
     * figure, each rounded as the claim's date has it, added up; zero when not overdue, null (n/a) when the figure is.
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
            charge = charge.add(figure.multiply(latePctOn(dueBy.plusDays(30 * boundary))).movePointLeft(2)
                    .setScale(2, rounding(date, asOf)));
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
        BigDecimal cap = decimalOn(CAP, asOf).orElse(null);
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
    }

    /** A live due of a claim dated on the date raised to the floor in force on it; null (n/a) stays null. */
    public BigDecimal floored(BigDecimal due, LocalDate date) {
        return due == null ? null : due.max(floorOn(date));
    }
}
