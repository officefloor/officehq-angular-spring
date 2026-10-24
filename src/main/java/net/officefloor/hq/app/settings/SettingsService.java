package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A sheet's settings: the role each user name has on it (a clerk unless made a steward) and its clerk
 * limit, the most a clerk may settle a claim for (none when null), and its floor, the least a claim's
 * live due can be, and its cap, the most a person's due-total can be (none when null), and its terms, how many days after its date a claim is due by (each grade may have its own
 * terms in their place), and its late percent, the
 * percent of its figure an overdue claim's due gains for every full 30 days it is overdue, and its policy
 * date, which decides how a claim's live figures round (the sheet's today when none is set), and its
 * period numbering from, the date from which a claim opened is numbered within its period (off when null),
 * and its proof threshold, above which a claim's due needs a proof before it is settled (none when null).
 * The settings that carry a figure (the clerk limit, the floor, the cap, the terms, the late percent and the proof threshold)
 * are dated: each value is recorded from a date (see {@link DatedSettings}); the policy date and the
 * period numbering from are not. */
@Service
public class SettingsService {

    private static final List<String> GRADES = List.of("A", "B", "C");

    private final SheetRepository sheets;
    private final SheetRoleRepository roles;
    private final SheetSettingRepository settings;
    private final DatedSettings dated;
    private final RollupService rollup;
    private final Audit audit;

    public SettingsService(SheetRepository sheets, SheetRoleRepository roles, SheetSettingRepository settings,
            DatedSettings dated, RollupService rollup, Audit audit) {
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

    /** The user's role on the sheet: a clerk unless made something else. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
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
}
