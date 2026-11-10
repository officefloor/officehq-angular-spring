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
    public static final String FLOOR = "floor";
    public static final String CAP = "cap";
    public static final String TERMS = "terms";
    /** The key of a grade's own terms: {@code terms-<grade>}. */
    public static final String GRADE_TERMS = "terms-";
    public static final String LATE_PCT = "late-pct";
    public static final String PROOF_THRESHOLD = "proof-threshold";

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

    /**
     * The values recorded for the settings that carry a figure (the floor, the late percent, the
     * terms, a grade's own terms, the clerk limit, the cap and the proof threshold), each from a from-date.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting", joinColumns = @JoinColumn(name = "sheet_id"))
    private List<DatedSetting> settings = new ArrayList<>();

    /** The date a claim's rounding turns on: half up before it, half to even on or after; null for the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The date numbering per period starts: on or after it a claim opened is numbered within its period; null for off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The days after a period's to-date it is left open once the sheet's today passes it; null for off. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** The last number given in the sheet's X sequence, to claims opened under numbering per period in no period. */
    @Column(name = "last_unperiod_claim_number", nullable = false)
    private int lastUnperiodClaimNumber;

    /** The permission matrix: for an action, the roles that may take it, comma separated; an action without keeps the standing rule. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_permission", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "action")
    @Column(name = "roles", nullable = false)
    private Map<String, String> permissions = new TreeMap<>();

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

    /**
     * Record a setting's value from a from-date, keeping the values already recorded; a value
     * already recorded for the key from that date is replaced. A null value is none.
     */
    public void record(String key, LocalDate fromDate, BigDecimal value) {
        settings.removeIf(s -> s.getKey().equals(key) && Objects.equals(s.getFromDate(), fromDate));
        settings.add(new DatedSetting(key, fromDate, value));
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

    /** The floor in force on the sheet's today. */
    public BigDecimal getFloor() {
        return floorOn(today);
    }

    /** The floor in force on a date: the least the live due of a claim of that date can be. */
    public BigDecimal floorOn(LocalDate date) {
        return inForce(FLOOR, date).map(DatedSetting::getValue).orElse(DEFAULT_FLOOR);
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

    /** The late percent in force on the sheet's today. */
    public BigDecimal getLatePct() {
        return latePctOn(today);
    }

    /** The late percent in force on a date: what an overdue claim of that date gains of its figure. */
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

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    /** Whether a claim opened on the sheet's today is numbered within its period. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** Give out the next number in the sheet's X sequence, for a claim in no period. */
    public int nextUnperiodClaimNumber() {
        return ++lastUnperiodClaimNumber;
    }

    /** Give out the next claim number on the sheet. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
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

    /** Whether a role may take an action: never a viewer; else by the permission matrix entry, else the standing rule. */
    public boolean permits(String action, String role) {
        if ("viewer".equals(role)) {
            return false;
        }
        String entry = permissions.get(action);
        if (entry == null || entry.isBlank()) {
            return STANDING_PERMISSIONS.getOrDefault(action, Set.of()).contains(role);
        }
        return Arrays.stream(entry.split(",")).map(String::trim).anyMatch(r -> r.equalsIgnoreCase(role));
    }
}
