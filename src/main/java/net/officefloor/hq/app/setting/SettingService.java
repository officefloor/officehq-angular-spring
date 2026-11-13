package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A sheet's settings: the role each user name has on it, and its clerk limit, its floor, its cap, its proof threshold, its terms
 *  (and any grade's own terms) and its late percent, its policy date and its period numbering from. The
 *  settings that carry a figure are dated: each value recorded is in force from its from-date. A claim's
 *  floor, late percent, terms and proof threshold are those in force on its date; the clerk limit and the cap those in
 *  force on the sheet's today. */
@Service
public class SettingService {

    /** The key prefix of a grade's own terms: terms-A, terms-B, terms-C. */
    private static final String GRADE_TERMS = "terms-";

    private final SheetService sheets;
    private final SheetRepository sheetRepository;
    private final SheetRoleRepository roles;
    private final SettingValueRepository values;
    private final SheetPermissionRepository permissions;
    private final Audit audit;

    public SettingService(SheetService sheets, SheetRepository sheetRepository, SheetRoleRepository roles,
            SettingValueRepository values, SheetPermissionRepository permissions, Audit audit) {
        this.sheets = sheets;
        this.sheetRepository = sheetRepository;
        this.roles = roles;
        this.values = values;
        this.permissions = permissions;
        this.audit = audit;
    }

    /** The roles assigned on the sheet, by user name. */
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserAsc(sheetId);
    }

    /** The user's role on the sheet: clerk unless assigned another. */
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
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

    /** The sheet's clerk limit in force on its today; null when there is none. */
    public BigDecimal clerkLimit(String sheetId) {
        return inForce(sheetId, "clerk-limit", sheets.today(sheetId)).map(SettingValue::getValue).orElse(null);
    }

    /** The sheet's floor in force on a date: the least the live due of a claim of that date can be. */
    public BigDecimal floor(String sheetId, LocalDate on) {
        return inForce(sheetId, "floor", on).map(SettingValue::getValue).orElse(START_FLOOR);
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

    /** A person's due-total held to the sheet's cap; a due-total of none (n/a) is not capped. */
    public BigDecimal capped(String sheetId, BigDecimal dueTotal) {
        BigDecimal cap = cap(sheetId);
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

    /** The sheet's late percent in force on a date: an overdue claim of that date's due gains it of its
     *  figure for every full 30 days it is overdue. */
    public BigDecimal latePct(String sheetId, LocalDate on) {
        return inForce(sheetId, "late-pct", on).map(SettingValue::getValue).orElse(START_LATE_PCT);
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

    /** How a claim dated on the given date rounds: half up before the policy date, half to even on or
     *  after it. */
    public RoundingMode roundingOn(String sheetId, LocalDate date) {
        return date.isBefore(policyDate(sheetId)) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
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

    /** Record the sheet's late percent from a date (null for the sheet's today). */
    @Transactional
    public SettingValue setLatePct(String sheetId, String by, BigDecimal latePct, LocalDate from) {
        return record(sheetId, by, "late-pct", latePct.setScale(2, RoundingMode.UNNECESSARY), from);
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
                + (value != null ? (key.startsWith("terms") ? String.valueOf(value.intValue()) : value.toPlainString()) : "")
                + (from != null ? " from=" + from : ""));
        return saved;
    }

    /** Change the sheet's policy date; null leaves it blank (the sheet's today). */
    @Transactional
    public Sheet setPolicyDate(String sheetId, String by, LocalDate policyDate) {
        sheets.load(sheetId);
        Sheet sheet = sheetRepository.findLockedById(sheetId).orElseThrow();
        sheet.setPolicyDate(policyDate);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
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
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=period-numbering-from value="
                + (from != null ? from.toString() : ""));
        return saved;
    }
}
