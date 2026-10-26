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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A sheet, chosen by its id. Everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String FLOOR = "floor";
    public static final String TERMS = "terms";
    public static final String LATE_PCT = "late-pct";
    public static final String CAP = "cap";
    public static final String PROOF_THRESHOLD = "proof-threshold";

    /** The floor, the terms and the late percent a sheet starts with, in force before any is recorded. */
    public static final BigDecimal DEFAULT_FLOOR = new BigDecimal("1.00");
    public static final int DEFAULT_TERMS = 14;
    public static final BigDecimal DEFAULT_LATE_PCT = new BigDecimal("0.00");

    @Id
    private String id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's own today: date decisions and default dates use it, never the real clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /**
     * The values recorded for the sheet's dated settings: the clerk limit, the floor, the terms (the sheet's and each
     * grade's), the late percent and the cap, each from its from-date.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting", joinColumns = @JoinColumn(name = "sheet_id"))
    private List<DatedSetting> settings = new ArrayList<>();

    /**
     * The permission matrix as set: for an action, the roles that may take it, comma separated. An action not listed
     * keeps the standing rule.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_permission", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "action_name")
    @Column(name = "roles")
    private Map<String, String> permissions = new HashMap<>();

    /** The date claims round by: one before it rounds half up, one on or after it half to even; null for today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The date from which claims opened are numbered within their period; null (blank) for off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /**
     * The days after its to-date an open period is left open: once its to-date plus these is before the sheet's
     * today, it is closed. Null (blank) for off.
     */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** Whether a steward has archived the sheet: it stays visible and every change on it is refused. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    protected Sheet() {
    }

    public Sheet(String id) {
        this.id = id;
        this.createdAt = Instant.now();
        this.today = LocalDate.now();
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

    /** The values recorded for the dated settings, each from its from-date. */
    public List<DatedSetting> getSettings() {
        return List.copyOf(settings);
    }

    /** Records the setting's value (null for none) from the date, replacing one already recorded from that date. */
    public void record(String key, LocalDate from, String value) {
        settings.removeIf(s -> s.getKey().equals(key) && s.getFrom().equals(from));
        settings.add(new DatedSetting(key, from, value));
    }

    /** The value recorded for the setting with the latest from-date on or before the date; empty with none. */
    private Optional<DatedSetting> inForce(String key, LocalDate on) {
        return settings.stream()
                .filter(s -> s.getKey().equals(key) && on != null && !s.getFrom().isAfter(on))
                .max(Comparator.comparing(DatedSetting::getFrom));
    }

    /** The most a clerk may settle a claim for, in force on the sheet's today; null means no limit. */
    public BigDecimal getClerkLimit() {
        return inForce(CLERK_LIMIT, today).map(DatedSetting::getValue).map(BigDecimal::new).orElse(null);
    }

    /** The least a claim's live due can be, in force on the date; the starting 1.00 before any is recorded. */
    public BigDecimal floorOn(LocalDate on) {
        return inForce(FLOOR, on).map(DatedSetting::getValue).map(BigDecimal::new).orElse(DEFAULT_FLOOR);
    }

    /** The days a claim dated on the date has to be settled in; the starting 14 before any are recorded. */
    public int termsOn(LocalDate on) {
        return inForce(TERMS, on).map(DatedSetting::getValue).map(Integer::valueOf).orElse(DEFAULT_TERMS);
    }

    /** The terms recorded for the grade in force on the date; empty when none are (the sheet's terms apply). */
    public Optional<Integer> gradeTermsOn(String grade, LocalDate on) {
        return inForce(TERMS + "-" + grade, on).map(DatedSetting::getValue).map(Integer::valueOf);
    }

    /** The terms a claim dated on the date has when its person has the grade: the grade's own, else the sheet's. */
    public int termsFor(String grade, LocalDate on) {
        return gradeTermsOn(grade, on).orElseGet(() -> termsOn(on));
    }

    /**
     * The percent of an overdue claim's figure added for a full 30 days it is overdue, in force on the date (each
     * 30-day boundary past its due-by takes the one in force then); the starting 0.00 before any is recorded.
     */
    public BigDecimal latePctOn(LocalDate on) {
        return inForce(LATE_PCT, on).map(DatedSetting::getValue).map(BigDecimal::new).orElse(DEFAULT_LATE_PCT);
    }

    /** The most a person's due-total can be, in force on the sheet's today; null means no cap. */
    public BigDecimal getCap() {
        return inForce(CAP, today).map(DatedSetting::getValue).map(BigDecimal::new).orElse(null);
    }

    /**
     * The due above which a claim dated on the date cannot be settled or approved without a proof, in force on that
     * date; null means none.
     */
    public BigDecimal proofThresholdOn(LocalDate on) {
        return inForce(PROOF_THRESHOLD, on).map(DatedSetting::getValue).map(BigDecimal::new).orElse(null);
    }

    /** The permission matrix as set, by action; an action not listed keeps the standing rule. */
    public Map<String, String> getPermissions() {
        return Map.copyOf(permissions);
    }

    /** Sets the roles that may take the action, comma separated; null (blank) for the standing rule. */
    public void setPermission(String action, String roles) {
        if (roles == null) {
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

    public boolean isArchived() {
        return archived;
    }

    public void archive() {
        this.archived = true;
    }

    /** The due-total (null for n/a, which is not capped) held to the cap when it is above it. */
    public BigDecimal capped(BigDecimal dueTotal) {
        BigDecimal cap = getCap();
        return cap != null && dueTotal != null && dueTotal.compareTo(cap) > 0 ? cap.setScale(2) : dueTotal;
    }

    /**
     * How a claim's live figures round, by the date its rounding follows: half up before the policy date (the
     * sheet's today when it is blank), half to even on or after it.
     */
    public RoundingMode roundingOn(LocalDate roundsOn) {
        LocalDate policy = policyDate != null ? policyDate : today;
        return policy != null && roundsOn.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }
}
