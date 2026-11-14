package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ProofKind;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A sheet's settings: the role each user name has on it, and its clerk limit, its floor, its cap, its proof threshold, its terms
 *  (and any grade's own terms) and its late percent, its policy date, its period numbering from, its grace days and its entry date. The
 *  settings that carry a figure are dated: each value recorded is in force from its from-date. A claim's
 *  floor, late percent, terms and proof threshold are those in force on its date; the clerk limit and the cap those in
 *  force on the sheet's today. */
@Service
public class SettingService {

    /** The key prefix of a grade's own terms: terms-A, terms-B, terms-C. */
    private static final String GRADE_TERMS = "terms-";

    /** The key prefix of a grade's grace days: grace-A, grace-B, grace-C. */
    private static final String GRADE_GRACE = "grace-";

    /** The key prefix of a grade's proof threshold: proof-threshold-A, proof-threshold-B, proof-threshold-C. */
    private static final String GRADE_PROOF_THRESHOLD = "proof-threshold-";

    private final SheetService sheets;
    private final SheetRepository sheetRepository;
    private final SheetRoleRepository roles;
    private final SettingValueRepository values;
    private final SheetPermissionRepository permissions;
    private final DelegationRepository delegations;
    private final History history;
    private final Audit audit;

    public SettingService(SheetService sheets, SheetRepository sheetRepository, SheetRoleRepository roles,
            SettingValueRepository values, SheetPermissionRepository permissions, DelegationRepository delegations,
            History history, Audit audit) {
        this.history = history;
        this.sheets = sheets;
        this.sheetRepository = sheetRepository;
        this.roles = roles;
        this.values = values;
        this.permissions = permissions;
        this.delegations = delegations;
        this.audit = audit;
    }

    /** The keys the values of the settings that are not dated are kept under (see {@link History#settings}). */
    private static final String ROLE = "role:";
    private static final String PERMISSION = "perm:";
    private static final String NUMBERING_FROM = "period-numbering-from";
    private static final String GRACE_DAYS = "grace-days";
    private static final String ESCALATE_AFTER = "escalate-after";
    private static final String PROOF_KINDS = "proof-kinds";
    private static final String REVIEW_SLA = "review-sla";
    private static final String ENTRY_DATE = "entry-date";
    private static final String POT = "pot";
    private static final String ALERT_THRESHOLD = "alert-threshold";
    private static final String FISCAL_START_MONTH = "fiscal-start-month";

    /** The sheet's settings as they stood at the end of a past day: each value as it stood set then,
     *  the day standing in for the sheet's today in those in force on a date. */
    public record AsOfView(Map<String, String> roles, Map<String, String> permissions, LocalDate periodNumberingFrom,
            Integer graceDays, List<Delegation> delegations, BigDecimal clerkLimit, BigDecimal floor, BigDecimal cap,
            int terms, BigDecimal latePct, LocalDate policyDate, Map<String, Integer> gradeTerms,
            Map<String, List<SettingValue>> dated, LocalDate entryDate, BigDecimal pot, String parent,
            int fiscalStartMonth, BigDecimal alertThreshold, Integer escalateAfter, Integer reviewSla,
            List<String> proofKinds) {
    }

    /** The sheet's settings as they stood at the end of a day. A setting that is not dated whose
     *  value was never kept stands as it is now; one kept only after the day was not set then. */
    public AsOfView asOf(String sheetId, LocalDate day) {
        Map<String, String> then = history.settings(sheetId, day);
        List<String> kept = history.settingsKept(sheetId);
        Map<String, String> byUser = new TreeMap<>();
        roles(sheetId).forEach(r -> byUser.put(r.getUser(), r.getRole().label()));
        Map<String, String> byAction = permissions(sheetId);
        for (String key : kept) {
            String value = then.get(key);
            if (key.startsWith(ROLE)) {
                String user = key.substring(ROLE.length());
                if (value == null) {
                    byUser.remove(user);
                } else {
                    byUser.put(user, value);
                }
            } else if (key.startsWith(PERMISSION)) {
                byAction.put(key.substring(PERMISSION.length()), value != null ? value : "");
            }
        }
        String numbering = kept.contains(NUMBERING_FROM) ? then.get(NUMBERING_FROM)
                : Objects.toString(periodNumberingFrom(sheetId), null);
        String entry = kept.contains(ENTRY_DATE) ? then.get(ENTRY_DATE) : Objects.toString(entryDate(sheetId), null);
        String grace = kept.contains(GRACE_DAYS) ? then.get(GRACE_DAYS) : Objects.toString(graceDays(sheetId), null);
        String escalate = kept.contains(ESCALATE_AFTER) ? then.get(ESCALATE_AFTER)
                : Objects.toString(escalateAfter(sheetId), null);
        String kinds = kept.contains(PROOF_KINDS) ? then.get(PROOF_KINDS) : sheets.load(sheetId).getProofKinds();
        String sla = kept.contains(REVIEW_SLA) ? then.get(REVIEW_SLA) : Objects.toString(reviewSla(sheetId), null);
        BigDecimal potNow = pot(sheetId);
        String pot = kept.contains(POT) ? then.get(POT) : potNow != null ? potNow.toPlainString() : null;
        BigDecimal alertNow = alertThreshold(sheetId);
        String alert = kept.contains(ALERT_THRESHOLD) ? then.get(ALERT_THRESHOLD)
                : alertNow != null ? alertNow.toPlainString() : null;
        String parent = parent(sheetId);
        String fiscal = kept.contains(FISCAL_START_MONTH) ? then.get(FISCAL_START_MONTH)
                : String.valueOf(fiscalStartMonth(sheetId));
        Map<String, List<SettingValue>> dated = history.dated(sheetId, day);
        Map<String, Integer> gradeTerms = new LinkedHashMap<>();
        dated.forEach((key, recorded) -> {
            if (key.startsWith(GRADE_TERMS)) {
                recorded.stream().filter(v -> !v.getFrom().isAfter(day)).reduce((a, b) -> b)
                        .ifPresent(v -> gradeTerms.put(key.substring(GRADE_TERMS.length()), v.getValue().intValue()));
            }
        });
        return new AsOfView(byUser, byAction, numbering != null ? LocalDate.parse(numbering) : null,
                grace != null ? Integer.valueOf(grace) : null,
                delegations(sheetId).stream().filter(d -> d.madeAsOf(day)).toList(),
                inForce(sheetId, "clerk-limit", day, day).map(SettingValue::getValue).orElse(null),
                floor(sheetId, day, day),
                inForce(sheetId, "cap", day, day).map(SettingValue::getValue).orElse(null),
                inForce(sheetId, "terms", day, day).map(v -> v.getValue().intValue()).orElse(START_TERMS),
                latePct(sheetId, day, day), history.policyDate(sheetId, day), gradeTerms, dated,
                entry != null ? LocalDate.parse(entry) : null, pot != null ? new BigDecimal(pot) : null, parent,
                fiscal != null ? Integer.parseInt(fiscal) : 1, alert != null ? new BigDecimal(alert) : null,
                escalate != null ? Integer.valueOf(escalate) : null, sla != null ? Integer.valueOf(sla) : null,
                kindsOf(kinds));
    }

    /** The roles assigned on the sheet, by user name. */
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserAsc(sheetId);
    }

    /** The user's role on the sheet as it acts: a steward while a delegation to it is in force on the
     *  sheet's today, else its own role. */
    public Role roleOf(String sheetId, String user) {
        return delegatedBy(sheetId, user).isPresent() ? Role.STEWARD : ownRole(sheetId, user);
    }

    /** The role assigned the user on the sheet: clerk unless assigned another. */
    public Role ownRole(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** The steward who delegated the steward role to the user by a delegation in force on the sheet's
     *  today (the latest made); empty when none is. */
    public Optional<String> delegatedBy(String sheetId, String user) {
        List<Delegation> made = delegations.findBySheetIdAndUserOrderByIdDesc(sheetId, user);
        if (made.isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = sheets.today(sheetId);
        return made.stream().filter(d -> d.covers(today)).map(Delegation::getBy).findFirst();
    }

    /** The delegations made on the sheet, in the order made. */
    public List<Delegation> delegations(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Delegate the steward role to a user from a from-date (null for the sheet's today) to a to-date
     *  (null for the from-date), inclusive; anyone but a steward by their own role, or a to-date before
     *  the from-date, is refused. */
    @Transactional
    public Delegation delegate(String sheetId, String by, String user, LocalDate from, LocalDate to) {
        sheets.lock(sheetId);
        if (ownRole(sheetId, by) != Role.STEWARD) {
            throw new DelegationRefusedException("Only a steward can delegate the steward role");
        }
        LocalDate start = from != null ? from : sheets.today(sheetId);
        LocalDate end = to != null ? to : start;
        if (end.isBefore(start)) {
            throw new DelegationRefusedException("The to-date is before the from-date");
        }
        Delegation saved = delegations.saveAndFlush(new Delegation(sheetId, user, start, end, by, sheets.today(sheetId)));
        audit.recordOnCommit("DELEGATED sheet=" + sheetId + " by=" + by + " user=" + user + " from=" + start
                + " to=" + end);
        return saved;
    }

    /** Whether the user is a viewer on the sheet: one who can change nothing. */
    public boolean isViewer(String sheetId, String user) {
        return roleOf(sheetId, user) == Role.VIEWER;
    }

    /** The sheet's permission matrix as set, by action label, every action listed: its roles comma
     *  separated, blank when it keeps the standing rule. */
    public Map<String, String> permissions(String sheetId) {
        Map<String, String> byAction = new LinkedHashMap<>();
        for (Permission action : Permission.values()) {
            byAction.put(action.label(), "");
        }
        permissions.findBySheetId(sheetId).forEach(p -> byAction.put(p.getAction(), p.getRoles()));
        return byAction;
    }

    /** Whether the user's role may take the action on the sheet: by the roles the matrix lists for it,
     *  else by the standing rule. */
    public boolean allows(String sheetId, String user, Permission action) {
        Role role = roleOf(sheetId, user);
        if (role == Role.VIEWER) {
            return false;
        }
        return permissions.findBySheetIdAndAction(sheetId, action.label())
                .map(p -> Arrays.stream(p.getRoles().split(",")).anyMatch(r -> r.equals(role.label())))
                .orElseGet(() -> action.standing().contains(role));
    }

    /** Refuse the action unless the user's role may take it on the sheet. */
    public void refuseUnless(String sheetId, String user, Permission action) {
        if (!allows(sheetId, user, action)) {
            throw new PermissionRefusedException(user, action);
        }
    }

    /** Set the roles that may take an action on the sheet; none keeps the standing rule. */
    @Transactional
    public void setPermission(String sheetId, String by, Permission action, List<Role> allowed) {
        sheets.lock(sheetId);
        String value = String.join(",", allowed.stream().map(Role::label).toList());
        Optional<SheetPermission> existing = permissions.findBySheetIdAndAction(sheetId, action.label());
        if (value.isEmpty()) {
            existing.ifPresent(p -> {
                permissions.delete(p);
                permissions.flush();
            });
        } else {
            SheetPermission saved = existing.orElseGet(() -> new SheetPermission(sheetId, action.label(), value));
            saved.setRoles(value);
            permissions.saveAndFlush(saved);
        }
        history.settingSet(sheetId, PERMISSION + action.label(), value.isEmpty() ? null : value);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=perm-" + action.label()
                + " value=" + value);
    }

    /** The floor a sheet starts with: in force before the first from-date recorded for it. */
    public static final BigDecimal START_FLOOR = new BigDecimal("1.00");

    /** The late percent a sheet starts with. */
    public static final BigDecimal START_LATE_PCT = new BigDecimal("0.00");

    /** The terms a sheet starts with, in days. */
    public static final int START_TERMS = 14;

    /** The values recorded for the sheet's dated settings, by key, each list by from-date. */
    public Map<String, List<SettingValue>> dated(String sheetId) {
        Map<String, List<SettingValue>> byKey = new TreeMap<>();
        values.findBySheetIdOrderByKeyAscFromAsc(sheetId)
                .forEach(v -> byKey.computeIfAbsent(v.getKey(), k -> new ArrayList<>()).add(v));
        return byKey;
    }

    /** The value recorded for a dated setting that is in force on a date: the one with the latest
     *  from-date on or before it; empty before every from-date (the sheet's starting value applies). */
    private Optional<SettingValue> inForce(String sheetId, String key, LocalDate on) {
        return values.findFirstBySheetIdAndKeyAndFromLessThanEqualOrderByFromDesc(sheetId, key, on);
    }

    /** A dated setting's value in force on a date as the values stood at the end of a day (null: as
     *  they stand now). */
    private Optional<SettingValue> inForce(String sheetId, String key, LocalDate on, LocalDate asOf) {
        return asOf == null ? inForce(sheetId, key, on) : history.setting(sheetId, key, on, asOf);
    }

    /** The sheet's clerk limit in force on its today; null when there is none. */
    public BigDecimal clerkLimit(String sheetId) {
        return inForce(sheetId, "clerk-limit", sheets.today(sheetId)).map(SettingValue::getValue).orElse(null);
    }

    /** The sheet's floor in force on a date: the least the live due of a claim of that date can be. */
    public BigDecimal floor(String sheetId, LocalDate on) {
        return floor(sheetId, on, null);
    }

    /** The sheet's floor in force on a date as it stood at the end of a day (null: as it stands now). */
    public BigDecimal floor(String sheetId, LocalDate on, LocalDate asOf) {
        return inForce(sheetId, "floor", on, asOf).map(SettingValue::getValue).orElse(START_FLOOR);
    }

    /** The sheet's cap in force on its today: the most a person's due-total can be; null when there is none. */
    public BigDecimal cap(String sheetId) {
        return inForce(sheetId, "cap", sheets.today(sheetId)).map(SettingValue::getValue).orElse(null);
    }

    /** The sheet's proof threshold in force on a date: a claim of that date whose due is above it cannot
     *  be settled or approved while it has no proofs; null when there is none. */
    public BigDecimal proofThreshold(String sheetId, LocalDate on) {
        return inForce(sheetId, "proof-threshold", on).map(SettingValue::getValue).orElse(null);
    }

    /** The proof threshold in force on a date for a claim of the grade: the grade's own in force then
     *  when one is set (not blank), else the sheet's; null when neither is. */
    public BigDecimal proofThresholdFor(String sheetId, String grade, LocalDate on) {
        if (grade != null) {
            BigDecimal own = inForce(sheetId, GRADE_PROOF_THRESHOLD + grade, on).map(SettingValue::getValue).orElse(null);
            if (own != null) {
                return own;
            }
        }
        return proofThreshold(sheetId, on);
    }

    /** The sheet's note threshold in force on a date: a claim of that date whose due is above it cannot
     *  be opened while its note is empty; null when there is none. */
    public BigDecimal noteThreshold(String sheetId, LocalDate on) {
        return inForce(sheetId, "note-threshold", on).map(SettingValue::getValue).orElse(null);
    }

    /** The sheet's review threshold in force on a date: a claim of that date whose due is above it goes
     *  to REVIEW when opened; null when there is none. */
    public BigDecimal reviewThreshold(String sheetId, LocalDate on) {
        return inForce(sheetId, "review-threshold", on).map(SettingValue::getValue).orElse(null);
    }

    /** A person's due-total held to the sheet's cap; a due-total of none (n/a) is not capped. */
    public BigDecimal capped(String sheetId, BigDecimal dueTotal) {
        return capped(sheetId, dueTotal, null);
    }

    /** A person's due-total held to the cap in force at the end of a day, as it stood then, the day
     *  standing in for the sheet's today (null: the cap in force on the sheet's today now). */
    public BigDecimal capped(String sheetId, BigDecimal dueTotal, LocalDate asOf) {
        BigDecimal cap = asOf == null ? cap(sheetId)
                : inForce(sheetId, "cap", asOf, asOf).map(SettingValue::getValue).orElse(null);
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
    }

    /** The sheet's terms in days in force on a date: a claim of that date is due by its date plus them. */
    public int terms(String sheetId, LocalDate on) {
        return inForce(sheetId, "terms", on).map(v -> v.getValue().intValue()).orElse(START_TERMS);
    }

    /** The grades' own terms in force on a date, by grade; a grade not listed takes the sheet's terms. */
    public Map<String, Integer> gradeTerms(String sheetId, LocalDate on) {
        Map<String, Integer> byGrade = new LinkedHashMap<>();
        dated(sheetId).forEach((key, recorded) -> {
            if (!key.startsWith(GRADE_TERMS)) {
                return;
            }
            recorded.stream().filter(v -> !v.getFrom().isAfter(on)).reduce((a, b) -> b)
                    .ifPresent(v -> byGrade.put(key.substring(GRADE_TERMS.length()), v.getValue().intValue()));
        });
        return byGrade;
    }

    /** The terms in days a claim of the grade dated on a date takes: the grade's own in force then,
     *  else the sheet's. */
    public int termsFor(String sheetId, String grade, LocalDate on) {
        if (grade == null) {
            return terms(sheetId, on);
        }
        return inForce(sheetId, GRADE_TERMS + grade, on).map(v -> v.getValue().intValue())
                .orElseGet(() -> terms(sheetId, on));
    }

    /** The grace days in force on a date for a claim of the grade: an OPEN or PENDING claim of that
     *  date is overdue only once its due-by plus them is before the sheet's today, and its late
     *  charge's 30-day boundaries count from there; 0 when the grade has none in force then (blank). */
    public int graceFor(String sheetId, String grade, LocalDate on, LocalDate asOf) {
        if (grade == null) {
            return 0;
        }
        return inForce(sheetId, GRADE_GRACE + grade, on, asOf).map(SettingValue::getValue)
                .map(BigDecimal::intValue).orElse(0);
    }

    /** The sheet's late percent in force on a date: an overdue claim of that date's due gains it of its
     *  figure for every full 30 days it is overdue. */
    public BigDecimal latePct(String sheetId, LocalDate on) {
        return latePct(sheetId, on, null);
    }

    /** The sheet's late percent in force on a date as it stood at the end of a day (null: as it
     *  stands now). */
    public BigDecimal latePct(String sheetId, LocalDate on, LocalDate asOf) {
        return inForce(sheetId, "late-pct", on, asOf).map(SettingValue::getValue).orElse(START_LATE_PCT);
    }

    /** The sheet's policy date as set; null when it is blank (the sheet's today applies). */
    public LocalDate policyDateSet(String sheetId) {
        return sheets.load(sheetId).getPolicyDate();
    }

    /** The sheet's policy date in force: as set, else the sheet's today. */
    public LocalDate policyDate(String sheetId) {
        LocalDate set = policyDateSet(sheetId);
        return set != null ? set : sheets.today(sheetId);
    }

    /** The sheet's period numbering from; null when it is blank (off). */
    public LocalDate periodNumberingFrom(String sheetId) {
        return sheets.load(sheetId).getPeriodNumberingFrom();
    }

    /** The sheet's entry date; null when it is blank (off). */
    public LocalDate entryDate(String sheetId) {
        return sheets.load(sheetId).getEntryDate();
    }

    /** The date a new claim given none takes: the sheet's entry date while it is set, else its today. */
    public LocalDate defaultClaimDate(String sheetId) {
        LocalDate entry = entryDate(sheetId);
        return entry != null ? entry : sheets.today(sheetId);
    }

    /** The sheet's pot (two decimals); null when it is blank (none). */
    public BigDecimal pot(String sheetId) {
        return sheets.load(sheetId).getPot();
    }

    /** The sheet's alert threshold (two decimals); null when it is blank (off). */
    public BigDecimal alertThreshold(String sheetId) {
        return sheets.load(sheetId).getAlertThreshold();
    }

    /** The sheet's parent sheet's id; null when it has none. Not kept in the sheet's history: the
     *  sheet shown as of a past day shows its parent as it stands now. */
    public String parent(String sheetId) {
        return sheets.load(sheetId).getParentId();
    }

    /** The sheet's fiscal year start month, 1 to 12; 1 unless set. */
    public int fiscalStartMonth(String sheetId) {
        return sheets.load(sheetId).getFiscalStartMonth();
    }

    /** The sheet's grace days; null when they are blank (off). */
    public Integer graceDays(String sheetId) {
        return sheets.load(sheetId).getGraceDays();
    }

    /** The sheet's escalation gap in days; null when it is blank (off). */
    public Integer escalateAfter(String sheetId) {
        return sheets.load(sheetId).getEscalateAfter();
    }

    /** The sheet's proof kinds: a claim above the proof threshold needs a proof of each before it can
     *  be settled or approved; empty when none are set. */
    public List<String> proofKinds(String sheetId) {
        return kindsOf(sheets.load(sheetId).getProofKinds());
    }

    private static List<String> kindsOf(String kinds) {
        return kinds == null || kinds.isBlank() ? List.of() : List.of(kinds.split(","));
    }

    /** The sheet's review SLA in days; null when it is blank (none). */
    public Integer reviewSla(String sheetId) {
        return sheets.load(sheetId).getReviewSla();
    }

    /** The rounding modes a sheet may set, by the word that names them. */
    private static final Map<String, RoundingMode> ROUNDING_MODES = Map.of(
            "half-up", RoundingMode.HALF_UP, "half-even", RoundingMode.HALF_EVEN);

    /** How a claim dated on the given date rounds: by the rounding mode in force on it when one is,
     *  else half up before the policy date, half to even on or after it. */
    public RoundingMode roundingOn(String sheetId, LocalDate date) {
        return roundingOn(sheetId, date, null);
    }

    /** How a claim dated on the given date rounds by the policy date as it stood set at the end of a
     *  day, the day standing in for the sheet's today (null: as it stands now). */
    public RoundingMode roundingOn(String sheetId, LocalDate date, LocalDate asOf) {
        RoundingMode mode = inForce(sheetId, "rounding", date, asOf).map(SettingValue::getText)
                .map(ROUNDING_MODES::get).orElse(null);
        if (mode != null) {
            return mode;
        }
        LocalDate policy = asOf == null ? policyDate(sheetId) : history.policyDate(sheetId, asOf);
        if (policy == null) {
            policy = asOf;
        }
        return date.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** Assign a user name a role on the sheet. */
    @Transactional
    public SheetRole setRole(String sheetId, String by, String user, Role role) {
        sheets.lock(sheetId);
        SheetRole saved = roles.findBySheetIdAndUser(sheetId, user)
                .map(existing -> {
                    existing.setRole(role);
                    return existing;
                })
                .orElseGet(() -> new SheetRole(sheetId, user, role));
        saved = roles.saveAndFlush(saved);
        history.settingSet(sheetId, ROLE + user, role.label());
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + by + " user=" + user + " role=" + role.label());
        return saved;
    }

    /** Record the sheet's clerk limit from a date (null for the sheet's today); a null limit records none. */
    @Transactional
    public SettingValue setClerkLimit(String sheetId, String by, BigDecimal limit, LocalDate from) {
        return record(sheetId, by, "clerk-limit", limit != null ? limit.setScale(2, RoundingMode.UNNECESSARY) : null, from);
    }

    /** Record the sheet's floor from a date (null for the sheet's today). */
    @Transactional
    public SettingValue setFloor(String sheetId, String by, BigDecimal floor, LocalDate from) {
        return record(sheetId, by, "floor", floor.setScale(2, RoundingMode.UNNECESSARY), from);
    }

    /** Record the sheet's cap from a date (null for the sheet's today); a null cap records none. */
    @Transactional
    public SettingValue setCap(String sheetId, String by, BigDecimal cap, LocalDate from) {
        return record(sheetId, by, "cap", cap != null ? cap.setScale(2, RoundingMode.UNNECESSARY) : null, from);
    }

    /** Record the sheet's proof threshold from a date (null for the sheet's today); a null threshold
     *  records none. */
    @Transactional
    public SettingValue setProofThreshold(String sheetId, String by, BigDecimal threshold, LocalDate from) {
        return record(sheetId, by, "proof-threshold",
                threshold != null ? threshold.setScale(2, RoundingMode.UNNECESSARY) : null, from);
    }

    /** Record a grade's proof threshold on the sheet from a date (null for the sheet's today); null
     *  records none (blank: the sheet's applies). */
    @Transactional
    public SettingValue setGradeProofThreshold(String sheetId, String by, String grade, BigDecimal threshold, LocalDate from) {
        return record(sheetId, by, GRADE_PROOF_THRESHOLD + grade,
                threshold != null ? threshold.setScale(2, RoundingMode.UNNECESSARY) : null, from);
    }

    /** Record the sheet's note threshold from a date (null for the sheet's today); a null threshold
     *  records none. */
    @Transactional
    public SettingValue setNoteThreshold(String sheetId, String by, BigDecimal threshold, LocalDate from) {
        return record(sheetId, by, "note-threshold",
                threshold != null ? threshold.setScale(2, RoundingMode.UNNECESSARY) : null, from);
    }

    /** Record the sheet's review threshold from a date (null for the sheet's today); a null threshold
     *  records none. */
    @Transactional
    public SettingValue setReviewThreshold(String sheetId, String by, BigDecimal threshold, LocalDate from) {
        return record(sheetId, by, "review-threshold",
                threshold != null ? threshold.setScale(2, RoundingMode.UNNECESSARY) : null, from);
    }

    /** Record the sheet's terms from a date (null for the sheet's today). */
    @Transactional
    public SettingValue setTerms(String sheetId, String by, int terms, LocalDate from) {
        return record(sheetId, by, "terms", BigDecimal.valueOf(terms), from);
    }

    /** Record a grade's own terms on the sheet from a date (null for the sheet's today). */
    @Transactional
    public SettingValue setGradeTerms(String sheetId, String by, String grade, int terms, LocalDate from) {
        return record(sheetId, by, GRADE_TERMS + grade, BigDecimal.valueOf(terms), from);
    }

    /** Record a grade's grace days on the sheet from a date (null for the sheet's today); null
     *  records none (blank). */
    @Transactional
    public SettingValue setGradeGrace(String sheetId, String by, String grade, Integer days, LocalDate from) {
        return record(sheetId, by, GRADE_GRACE + grade, days != null ? BigDecimal.valueOf(days) : null, from);
    }

    /** Record the sheet's late percent from a date (null for the sheet's today). */
    @Transactional
    public SettingValue setLatePct(String sheetId, String by, BigDecimal latePct, LocalDate from) {
        return record(sheetId, by, "late-pct", latePct.setScale(2, RoundingMode.UNNECESSARY), from);
    }

    /** Record the sheet's rounding mode (half-up or half-even) from a date (null for the sheet's today);
     *  null records none (the policy date decides); any other is refused. */
    @Transactional
    public SettingValue setRounding(String sheetId, String by, String mode, LocalDate from) {
        if (mode != null && !ROUNDING_MODES.containsKey(mode)) {
            throw new IllegalArgumentException("No rounding mode " + mode);
        }
        sheets.lock(sheetId);
        LocalDate on = from != null ? from : sheets.today(sheetId);
        SettingValue saved = values.findBySheetIdAndKeyAndFrom(sheetId, "rounding", on)
                .map(existing -> {
                    existing.setText(mode);
                    return existing;
                })
                .orElseGet(() -> new SettingValue(sheetId, "rounding", on, null, mode));
        saved = values.saveAndFlush(saved);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=rounding value="
                + (mode != null ? mode : "") + (from != null ? " from=" + from : ""));
        return saved;
    }

    /** Record a dated setting's value from a date, the sheet's today when none is given, keeping the
     *  values already recorded; one already recorded from that date is replaced. */
    private SettingValue record(String sheetId, String by, String key, BigDecimal value, LocalDate from) {
        sheets.lock(sheetId);
        LocalDate on = from != null ? from : sheets.today(sheetId);
        SettingValue saved = values.findBySheetIdAndKeyAndFrom(sheetId, key, on)
                .map(existing -> {
                    existing.setValue(value);
                    return existing;
                })
                .orElseGet(() -> new SettingValue(sheetId, key, on, value));
        saved = values.saveAndFlush(saved);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=" + key + " value="
                + (value != null ? (wholeDays(key) ? String.valueOf(value.intValue()) : value.toPlainString()) : "")
                + (from != null ? " from=" + from : ""));
        return saved;
    }

    /** Whether a dated setting's values are whole days: the terms, a grade's terms and a grade's grace. */
    public static boolean wholeDays(String key) {
        return key.startsWith("terms") || key.startsWith(GRADE_GRACE);
    }

    /** Change the sheet's policy date; null leaves it blank (the sheet's today). */
    @Transactional
    public Sheet setPolicyDate(String sheetId, String by, LocalDate policyDate) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setPolicyDate(policyDate);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        history.policyDateSet(sheetId);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=policy-date value="
                + (policyDate != null ? policyDate.toString() : ""));
        return saved;
    }

    /** Change the sheet's period numbering from; null leaves it blank (off). */
    @Transactional
    public Sheet setPeriodNumberingFrom(String sheetId, String by, LocalDate from) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setPeriodNumberingFrom(from);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        history.settingSet(sheetId, NUMBERING_FROM, from != null ? from.toString() : null);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=period-numbering-from value="
                + (from != null ? from.toString() : ""));
        return saved;
    }

    /** Change the sheet's entry date; null leaves it blank (off). */
    @Transactional
    public Sheet setEntryDate(String sheetId, String by, LocalDate date) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setEntryDate(date);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        history.settingSet(sheetId, ENTRY_DATE, date != null ? date.toString() : null);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=entry-date value="
                + (date != null ? date.toString() : ""));
        return saved;
    }

    /** Change the sheet's grace days; null leaves them blank (off). */
    @Transactional
    public Sheet setGraceDays(String sheetId, String by, Integer days) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setGraceDays(days);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        history.settingSet(sheetId, GRACE_DAYS, days != null ? days.toString() : null);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=grace-days value="
                + (days != null ? days.toString() : ""));
        return saved;
    }

    /** Change the sheet's escalation gap; null leaves it blank (off). */
    @Transactional
    public Sheet setEscalateAfter(String sheetId, String by, Integer days) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setEscalateAfter(days);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        history.settingSet(sheetId, ESCALATE_AFTER, days != null ? days.toString() : null);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=escalate-after value="
                + (days != null ? days.toString() : ""));
        return saved;
    }

    /** Change the sheet's proof kinds: each a receipt or a photo, listed once; empty leaves them blank
     *  (none). A kind other than receipt or photo is refused. */
    @Transactional
    public Sheet setProofKinds(String sheetId, String by, List<String> kinds) {
        for (String kind : kinds) {
            if (!ProofKind.valid(kind)) {
                throw new IllegalArgumentException("A proof kind is receipt or photo, not " + kind);
            }
        }
        String value = String.join(",", kinds.stream().distinct().toList());
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setProofKinds(value.isEmpty() ? null : value);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        history.settingSet(sheetId, PROOF_KINDS, value.isEmpty() ? null : value);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=proof-kinds value=" + value);
        return saved;
    }

    /** Change the sheet's review SLA; null leaves it blank (none). */
    @Transactional
    public Sheet setReviewSla(String sheetId, String by, Integer days) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setReviewSla(days);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        history.settingSet(sheetId, REVIEW_SLA, days != null ? days.toString() : null);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=review-sla value="
                + (days != null ? days.toString() : ""));
        return saved;
    }

    /** Change the sheet's fiscal year start month; one not 1 to 12 is refused. */
    @Transactional
    public Sheet setFiscalStartMonth(String sheetId, String by, int month) {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("The fiscal year start month is 1 to 12");
        }
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setFiscalStartMonth(month);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        history.settingSet(sheetId, FISCAL_START_MONTH, String.valueOf(month));
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=fiscal-start-month value="
                + month);
        return saved;
    }

    /** Change the sheet's pot, kept to two decimals; null leaves it blank (none). */
    @Transactional
    public Sheet setPot(String sheetId, String by, BigDecimal pot) {
        sheets.load(sheetId);
        BigDecimal scaled = pot != null ? pot.setScale(2, RoundingMode.UNNECESSARY) : null;
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setPot(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        history.settingSet(sheetId, POT, scaled != null ? scaled.toPlainString() : null);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=pot value="
                + (scaled != null ? scaled.toPlainString() : ""));
        return saved;
    }

    /** Change the sheet's alert threshold, kept to two decimals; null leaves it blank (off). */
    @Transactional
    public Sheet setAlertThreshold(String sheetId, String by, BigDecimal threshold) {
        sheets.load(sheetId);
        BigDecimal scaled = threshold != null ? threshold.setScale(2, RoundingMode.UNNECESSARY) : null;
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setAlertThreshold(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        history.settingSet(sheetId, ALERT_THRESHOLD, scaled != null ? scaled.toPlainString() : null);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=alert-threshold value="
                + (scaled != null ? scaled.toPlainString() : ""));
        return saved;
    }

    /** Change the sheet's parent sheet; null leaves it blank (none). The sheet itself, a sheet that
     *  has this sheet as an ancestor, and a sheet never loaded are refused. */
    @Transactional
    public Sheet setParent(String sheetId, String by, String parentId) {
        sheets.load(sheetId);
        if (parentId != null) {
            if (parentId.equals(sheetId)) {
                throw new ParentRefusedException("A sheet cannot be its own parent.");
            }
            if (!sheetRepository.existsById(parentId)) {
                throw new ParentRefusedException("The parent sheet " + parentId + " has never been loaded.");
            }
            List<String> seen = new ArrayList<>();
            for (String up = parentId; up != null && !seen.contains(up);
                    up = sheetRepository.findById(up).map(Sheet::getParentId).orElse(null)) {
                if (up.equals(sheetId)) {
                    throw new ParentRefusedException("The sheet " + parentId + " has this sheet as an ancestor.");
                }
                seen.add(up);
            }
        }
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setParentId(parentId);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=parent value="
                + (parentId != null ? parentId : ""));
        return saved;
    }
}
