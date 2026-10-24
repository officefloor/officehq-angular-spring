package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A sheet's settings: the role each user name has on it (a clerk unless made a steward) and its clerk
 * limit, the most a clerk may settle a claim for (none when null), and its floor, the least a claim's
 * live due can be, and its cap, the most a person's due-total can be (none when null), and its terms, how many business days after its date a claim is due by (each grade may have its own
 * terms in their place), and its late percent, the
 * percent of its figure an overdue claim's due gains for every full 30 days it is overdue, and its policy
 * date, which decides how a claim's live figures round (the sheet's today when none is set), and its
 * period numbering from, the date from which a claim opened is numbered within its period (off when null),
 * and its proof threshold, above which a claim's due needs a proof before it is settled (none when null; each grade may
 * have its own in its place),
 * and its grace days, how many days after its to-date an open period is closed when the sheet's today
 * is changed (off when null), and each grade's grace days, how many days past its due-by a claim of the
 * grade is overdue from (none when null).
 * The settings that carry a figure (the clerk limit, the floor, the cap, the terms, the late percent, the proof threshold and the grades' grace days)
 * are dated: each value is recorded from a date (see {@link DatedSettings}); the policy date, the
 * period numbering from and the grace days are not. Its permission matrix, also not dated, gives for each {@link Action}
 * the roles that may take it; a blank entry keeps the action's standing rule. */
@Service
public class SettingsService {

    private static final List<String> GRADES = List.of("A", "B", "C");

    private final SheetRepository sheets;
    private final SheetRoleRepository roles;
    private final SheetSettingRepository settings;
    private final SheetPermissionRepository permissions;
    private final DatedSettings dated;
    private final RollupService rollup;
    private final DelegationService delegations;
    private final Audit audit;

    public SettingsService(SheetRepository sheets, SheetRoleRepository roles, SheetSettingRepository settings,
            SheetPermissionRepository permissions, DatedSettings dated, RollupService rollup,
            DelegationService delegations, Audit audit) {
        this.delegations = delegations;
        this.permissions = permissions;
        this.sheets = sheets;
        this.roles = roles;
        this.settings = settings;
        this.dated = dated;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The roles given on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUser(sheetId);
    }

    /** The user's role on the sheet: a clerk unless made something else, and a steward while the sheet's
     * today is inside a range the steward role is delegated to them for (see {@link DelegationService}). */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        Role given = delegations.givenRole(sheetId, user);
        if (given != Role.STEWARD && delegations.actingFor(sheetId, user).isPresent()) return Role.STEWARD;
        return given;
    }

    @Transactional
    public SheetRole setRole(String sheetId, String by, String user, Role role) {
        if (!sheets.existsById(sheetId)) throw new SheetNotFoundException();
        SheetRole given = roles.findBySheetIdAndUser(sheetId, user).orElse(null);
        if (given == null) {
            given = roles.saveAndFlush(new SheetRole(sheetId, user, role));
        } else {
            given.setRole(role);
            roles.flush();
        }
        audit.change("ROLE_SET", sheetId, by, "user=" + user, "role=" + role.name().toLowerCase());
        return given;
    }

    /** The sheet's permission matrix as set: for each action, the roles that may take it as a comma
     * separated list, blank for its standing rule. */
    @Transactional(readOnly = true)
    public Map<Action, String> permissions(String sheetId) {
        Map<Action, String> matrix = new EnumMap<>(Action.class);
        for (Action action : Action.values()) matrix.put(action, "");
        for (SheetPermission set : permissions.findBySheetId(sheetId)) matrix.put(set.getAction(), set.getRoles());
        return matrix;
    }

    /** Whether the user's role on the sheet may take {@code action}: one of the roles the matrix lists
     * for it, or of its standing rule when the matrix leaves it blank. */
    @Transactional(readOnly = true)
    public boolean allowed(String sheetId, String user, Action action) {
        String listed = permissions.findBySheetIdAndAction(sheetId, action).map(SheetPermission::getRoles).orElse("");
        Set<Role> may = listed.isBlank() ? action.standing() : rolesOf(listed);
        return may.contains(roleOf(sheetId, user));
    }

    /** Set the roles that may take {@code action} on the sheet: a comma separated list of roles, or blank
     * for its standing rule. */
    @Transactional
    public void setPermission(String sheetId, String by, Action action, String roles) {
        if (!sheets.existsById(sheetId)) throw new SheetNotFoundException();
        SheetPermission set = permissions.findBySheetIdAndAction(sheetId, action).orElse(null);
        if (set == null) {
            permissions.save(new SheetPermission(sheetId, action, roles));
        } else {
            set.setRoles(roles);
        }
        permissions.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=perm-" + action.key(), "value=" + roles);
    }

    private static Set<Role> rolesOf(String listed) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String role : listed.split(",")) {
            if (!role.isBlank()) roles.add(Role.valueOf(role.trim().toUpperCase(Locale.ROOT)));
        }
        return roles;
    }

    /** The clerk limit in force on the sheet's today; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return dated.clerkLimit(sheetId);
    }

    /** Record the sheet's clerk limit from {@code from} (the sheet's today when null); null records no limit. */
    @Transactional
    public void setClerkLimit(String sheetId, String by, BigDecimal limit, LocalDate from) {
        record(sheetId, by, DatedSettings.CLERK_LIMIT, limit == null ? null : limit.toPlainString(), from, false);
    }

    /** The floor in force on the sheet's today. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return dated.floorOn(sheetId, today(sheetId));
    }

    /** Record the sheet's floor from {@code from} (the sheet's today when null); the live dues of the
     * claims dated from then, and so the figures, follow it. */
    @Transactional
    public void setFloor(String sheetId, String by, BigDecimal floor, LocalDate from) {
        record(sheetId, by, DatedSettings.FLOOR, floor.toPlainString(), from, true);
    }

    /** The cap in force on the sheet's today, the most a person's due-total can be; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String sheetId) {
        return dated.cap(sheetId);
    }

    /** Record the sheet's cap from {@code from} (the sheet's today when null); null records no cap. The
     * due-totals, and so the figures, follow the cap in force on the sheet's today. */
    @Transactional
    public void setCap(String sheetId, String by, BigDecimal cap, LocalDate from) {
        record(sheetId, by, DatedSettings.CAP, cap == null ? null : cap.toPlainString(), from, true);
    }

    /** The sheet's terms in force on its today, in days. */
    @Transactional(readOnly = true)
    public int terms(String sheetId) {
        return dated.termsOn(sheetId, today(sheetId));
    }

    /** Record the sheet's terms from {@code from} (the sheet's today when null); only the claims added
     * from then on, dated on or after it, of a person whose grade has no terms of its own then, are due
     * by them. */
    @Transactional
    public void setTerms(String sheetId, String by, int terms, LocalDate from) {
        record(sheetId, by, DatedSettings.TERMS, String.valueOf(terms), from, false);
    }

    /** The grades with their own terms in force on the sheet's today, by grade, in days. */
    @Transactional(readOnly = true)
    public Map<String, Integer> gradeTerms(String sheetId) {
        LocalDate today = today(sheetId);
        Map<String, Integer> terms = new LinkedHashMap<>();
        for (String grade : GRADES) {
            Integer own = dated.gradeTermsOn(sheetId, grade, today);
            if (own != null) terms.put(grade, own);
        }
        return terms;
    }

    /** The terms a claim dated {@code date} of a person of {@code grade} is due by (see {@link DatedSettings#termsFor}). */
    @Transactional(readOnly = true)
    public int termsFor(String sheetId, String grade, LocalDate date) {
        return dated.termsFor(sheetId, grade, date);
    }

    /** Record the grade's own terms from {@code from} (the sheet's today when null); null records that
     * the grade uses the sheet's terms from then. Only the claims added from then on are due by them. */
    @Transactional
    public void setGradeTerms(String sheetId, String by, String grade, Integer terms, LocalDate from) {
        record(sheetId, by, DatedSettings.gradeTermsKey(grade), terms == null ? null : String.valueOf(terms), from,
                false);
    }

    /** The grades with grace days in force on the sheet's today, by grade, in days. */
    @Transactional(readOnly = true)
    public Map<String, Integer> gradeGraces(String sheetId) {
        LocalDate today = today(sheetId);
        Map<String, Integer> graces = new LinkedHashMap<>();
        for (String grade : GRADES) {
            Integer grace = dated.gradeGraceOn(sheetId, grade, today);
            if (grace != null) graces.put(grade, grace);
        }
        return graces;
    }

    /** Record the grade's grace days from {@code from} (the sheet's today when null); null records no
     * grace from then. A claim dated from then, of a person of the grade then, is overdue only once its
     * due-by plus them is past, and its late charge counts from there; the figures follow. */
    @Transactional
    public void setGradeGrace(String sheetId, String by, String grade, Integer days, LocalDate from) {
        record(sheetId, by, DatedSettings.gradeGraceKey(grade), days == null ? null : String.valueOf(days), from,
                true);
    }

    /** The late percent in force on the sheet's today. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId) {
        return dated.latePctOn(sheetId, today(sheetId));
    }

    /** Record the sheet's late percent from {@code from} (the sheet's today when null); the late charges
     * of the claims dated from then, and so the figures, follow it. */
    @Transactional
    public void setLatePct(String sheetId, String by, BigDecimal latePct, LocalDate from) {
        record(sheetId, by, DatedSettings.LATE_PCT, latePct.toPlainString(), from, true);
    }

    /** The proof threshold in force on the sheet's today; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThreshold(String sheetId) {
        return dated.proofThresholdOn(sheetId, today(sheetId));
    }

    /** The proof threshold in force on {@code date}: a claim dated then whose due is above it cannot be
     * settled or approved while it has no proofs; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThresholdOn(String sheetId, LocalDate date) {
        return dated.proofThresholdOn(sheetId, date);
    }

    /** Record the sheet's proof threshold from {@code from} (the sheet's today when null); null records
     * no threshold. */
    @Transactional
    public void setProofThreshold(String sheetId, String by, BigDecimal threshold, LocalDate from) {
        record(sheetId, by, DatedSettings.PROOF_THRESHOLD, threshold == null ? null : threshold.toPlainString(), from, false);
    }

    /** The grades with their own proof threshold in force on the sheet's today, by grade. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> gradeProofThresholds(String sheetId) {
        LocalDate today = today(sheetId);
        Map<String, BigDecimal> thresholds = new LinkedHashMap<>();
        for (String grade : GRADES) {
            BigDecimal own = dated.gradeProofThresholdOn(sheetId, grade, today);
            if (own != null) thresholds.put(grade, own);
        }
        return thresholds;
    }

    /** The proof threshold of a claim dated {@code date} of a person of {@code grade} (see
     * {@link DatedSettings#proofThresholdFor}). */
    @Transactional(readOnly = true)
    public BigDecimal proofThresholdFor(String sheetId, String grade, LocalDate date) {
        return dated.proofThresholdFor(sheetId, grade, date);
    }

    /** Record the grade's own proof threshold from {@code from} (the sheet's today when null); null
     * records that the grade uses the sheet's proof threshold from then. */
    @Transactional
    public void setGradeProofThreshold(String sheetId, String by, String grade, BigDecimal threshold, LocalDate from) {
        record(sheetId, by, DatedSettings.gradeProofThresholdKey(grade), threshold == null ? null : threshold.toPlainString(),
                from, false);
    }

    /** The note threshold in force on the sheet's today; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal noteThreshold(String sheetId) {
        return dated.noteThresholdOn(sheetId, today(sheetId));
    }

    /** The note threshold in force on {@code date}: a claim dated then whose due is above it cannot be
     * opened while its note is empty; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal noteThresholdOn(String sheetId, LocalDate date) {
        return dated.noteThresholdOn(sheetId, date);
    }

    /** Record the sheet's note threshold from {@code from} (the sheet's today when null); null records
     * no threshold. */
    @Transactional
    public void setNoteThreshold(String sheetId, String by, BigDecimal threshold, LocalDate from) {
        record(sheetId, by, DatedSettings.NOTE_THRESHOLD, threshold == null ? null : threshold.toPlainString(), from, false);
    }

    /** Every value recorded for the dated setting {@code key}, oldest from-date first. */
    @Transactional(readOnly = true)
    public List<SheetSetting> history(String sheetId, String key) {
        return dated.history(sheetId, key);
    }

    /** Record {@code value} for the dated setting {@code key} from {@code from} (the sheet's today when
     * null), keeping the values already recorded; one already recorded from that date is replaced. The
     * SETTING_CHANGED line ends with the from-date when one was given; when {@code figures} the ROLLUP
     * and TOTALS lines follow it. */
    private void record(String sheetId, String by, String key, String value, LocalDate from, boolean figures) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = figures ? rollup.snapshot(sheetId) : null;
        LocalDate on = from == null ? sheet.getToday() : from;
        SheetSetting recorded = settings.findBySheetIdAndKeyAndFrom(sheetId, key, on).orElse(null);
        if (recorded == null) {
            settings.save(new SheetSetting(sheetId, key, on, value));
        } else {
            recorded.setValue(value);
        }
        settings.flush();
        List<String> fields = new ArrayList<>(List.of("key=" + key, "value=" + (value == null ? "" : value)));
        if (from != null) fields.add("from=" + from);
        audit.change("SETTING_CHANGED", sheetId, by, fields.toArray(String[]::new));
        if (figures) rollup.recordSince(sheetId, by, before);
    }

    private LocalDate today(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
    }

    /** The sheet's policy date as set; null when it is the sheet's today. */
    @Transactional(readOnly = true)
    public LocalDate policyDate(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getPolicyDate();
    }

    /** Set the sheet's policy date; null makes it the sheet's today. The live figures' rounding follows it. */
    @Transactional
    public Sheet setPolicyDate(String sheetId, String by, LocalDate policyDate) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setPolicyDate(policyDate);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=policy-date",
                "value=" + (policyDate == null ? "" : policyDate.toString()));
        rollup.recordSince(sheetId, by, before);
        return sheet;
    }

    /** The sheet's period numbering from; null when it is off. */
    @Transactional(readOnly = true)
    public LocalDate periodNumberingFrom(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getPeriodNumberingFrom();
    }

    /** Set the sheet's period numbering from; null turns it off. Only the claims opened from then on follow it. */
    @Transactional
    public Sheet setPeriodNumberingFrom(String sheetId, String by, LocalDate from) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setPeriodNumberingFrom(from);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=period-numbering-from",
                "value=" + (from == null ? "" : from.toString()));
        return sheet;
    }

    /** The sheet's grace days; null when they are off. */
    @Transactional(readOnly = true)
    public Integer graceDays(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getGraceDays();
    }

    /** Set the sheet's grace days; null turns them off. They take effect when the sheet's today is next changed. */
    @Transactional
    public Sheet setGraceDays(String sheetId, String by, Integer graceDays) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setGraceDays(graceDays);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=grace-days", "value=" + (graceDays == null ? "" : graceDays));
        return sheet;
    }

    /** The sheet's entry date; null when it is off. */
    @Transactional(readOnly = true)
    public LocalDate entryDate(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getEntryDate();
    }

    /** Set the sheet's entry date; null turns it off. Only the claims added or edited from then on follow it. */
    @Transactional
    public Sheet setEntryDate(String sheetId, String by, LocalDate entryDate) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setEntryDate(entryDate);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=entry-date",
                "value=" + (entryDate == null ? "" : entryDate.toString()));
        return sheet;
    }

    /** The sheet's pot; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal pot(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getPot();
    }

    /** Set the sheet's pot; null for none. The balanced pools' cuts follow it. */
    @Transactional
    public Sheet setPot(String sheetId, String by, BigDecimal pot) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setPot(pot);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=pot", "value=" + (pot == null ? "" : pot.toPlainString()));
        return sheet;
    }
}
