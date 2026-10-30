package net.officefloor.hq.app.access;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may do what on a sheet: the role each user name has (clerk unless set to steward) and the
 * clerk limit, the most due a clerk may settle on a claim (none: no limit); and the sheet's floor, the
 * least live due a claim has; its terms, the days a claim has to be paid by from its date, and the terms
 * of each grade that has its own (a grade without uses the sheet's); and its late
 * percent, what an overdue claim's due gains of its figure for every full 30 days overdue; and its policy
 * date, the date a claim's rounding turns on (none: the sheet's today); and its period numbering from,
 * the date from which a claim opened is numbered within its period (none: off).
 */
@Service
public class AccessService {

    public static final String CLERK_LIMIT = "clerk-limit";

    public static final String FLOOR = "floor";

    public static final String TERMS = "terms";

    /** The key of a grade's own terms: terms-A, terms-B or terms-C. */
    public static String gradeTermsKey(String grade) {
        return TERMS + "-" + grade;
    }

    public static final String LATE_PCT = "late-pct";

    public static final String POLICY_DATE = "policy-date";

    public static final String PERIOD_NUMBERING_FROM = "period-numbering-from";

    private final SheetRoleRepository roles;
    private final SheetRepository sheetRows;
    private final SheetService sheets;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public AccessService(SheetRoleRepository roles, SheetRepository sheetRows, SheetService sheets,
            Rollup rollup, ApplicationEventPublisher events) {
        this.rollup = rollup;
        this.roles = roles;
        this.sheetRows = sheetRows;
        this.sheets = sheets;
        this.events = events;
    }

    /** The roles set on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserName(sheetId);
    }

    /** The user's role on the sheet: clerk unless one was set. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUserName(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return sheetRows.findById(sheetId).map(Sheet::getClerkLimit).orElse(null);
    }

    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return sheets.floor(sheetId);
    }

    @Transactional(readOnly = true)
    public int terms(String sheetId) {
        return sheets.terms(sheetId);
    }

    /** The grade's own terms; null when it has none (it uses the sheet's). */
    @Transactional(readOnly = true)
    public Integer gradeTerms(String sheetId, String grade) {
        return sheetRows.findById(sheetId).map(s -> s.getGradeTerms(grade)).orElse(null);
    }

    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId) {
        return sheets.latePct(sheetId);
    }

    /** The sheet's policy date as set; null when blank. */
    @Transactional(readOnly = true)
    public LocalDate policyDate(String sheetId) {
        return sheets.policyDate(sheetId);
    }

    /** The sheet's period numbering from as set; null when blank (off). */
    @Transactional(readOnly = true)
    public LocalDate periodNumberingFrom(String sheetId) {
        return sheets.periodNumberingFrom(sheetId);
    }

    /** Give a user name a role on the sheet, replacing the one it had. */
    @Transactional
    public SheetRole setRole(String sheetId, String user, String name, Role role) {
        sheets.load(sheetId, user);
        SheetRole row = roles.findBySheetIdAndUserName(sheetId, name).orElse(null);
        if (row == null) {
            row = roles.saveAndFlush(new SheetRole(sheetId, name, role));
        } else {
            row.setRole(role);
            roles.flush();
        }
        events.publishEvent(new RoleSet(sheetId, user, name, role.key()));
        return row;
    }

    /**
     * Save the settings given, as written (blank: none, or the default), in one change; a setting left
     * out (null) is kept as it is.
     */
    @Transactional
    public void setSettings(String sheetId, String user, String clerkLimit, String floor, String terms,
            Map<String, String> gradeTerms, String latePct, String policyDate, String periodNumberingFrom) {
        if (clerkLimit != null) {
            setClerkLimit(sheetId, user, clerkLimit.isEmpty() ? null : new BigDecimal(clerkLimit));
        }
        if (floor != null) {
            setFloor(sheetId, user, floor.isEmpty() ? null : new BigDecimal(floor));
        }
        if (terms != null) {
            setTerms(sheetId, user, terms.isEmpty() ? null : Integer.valueOf(terms));
        }
        gradeTerms.forEach((grade, days) -> {
            if (days != null) {
                setGradeTerms(sheetId, user, grade, days.isEmpty() ? null : Integer.valueOf(days));
            }
        });
        if (latePct != null) {
            setLatePct(sheetId, user, latePct.isEmpty() ? null : new BigDecimal(latePct));
        }
        if (policyDate != null) {
            setPolicyDate(sheetId, user, policyDate.isEmpty() ? null : LocalDate.parse(policyDate));
        }
        if (periodNumberingFrom != null) {
            setPeriodNumberingFrom(sheetId, user,
                    periodNumberingFrom.isEmpty() ? null : LocalDate.parse(periodNumberingFrom));
        }
    }

    /**
     * Save the sheet's terms in days; null puts back the ones a sheet starts with. A claim added from
     * then on whose grade has no terms of its own is due by its date plus them; a claim already added
     * keeps the due-by it has.
     */
    @Transactional
    public int setTerms(String sheetId, String user, Integer terms) {
        Sheet sheet = sheets.load(sheetId, user);
        int days = terms == null ? Sheet.DEFAULT_TERMS : terms;
        sheet.setTerms(days);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, TERMS, Integer.toString(days)));
        return days;
    }

    /**
     * Save a grade's own terms in days; null removes them, the grade then using the sheet's terms. A
     * claim added from then on whose person has the grade on its date is due by its date plus them; a
     * claim already added keeps the due-by it has.
     */
    @Transactional
    public Integer setGradeTerms(String sheetId, String user, String grade, Integer days) {
        Sheet sheet = sheets.load(sheetId, user);
        sheet.setGradeTerms(grade, days);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, gradeTermsKey(grade), days == null ? "" : days.toString()));
        return days;
    }

    /**
     * Save the sheet's late percent; null puts back the one a sheet starts with (0.00). Every late charge
     * not yet settled moves with it; a settled due keeps the one it had.
     */
    @Transactional
    public BigDecimal setLatePct(String sheetId, String user, BigDecimal latePct) {
        Sheet sheet = sheets.load(sheetId, user);
        BigDecimal scaled = latePct == null ? Sheet.DEFAULT_LATE_PCT : latePct.setScale(2, RoundingMode.HALF_UP);
        Rollup.Figures before = rollup.figures(sheetId);
        sheet.setLatePct(scaled);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, LATE_PCT, scaled.toPlainString()));
        rollup.changed(sheetId, user, before);
        return scaled;
    }

    /**
     * Save the sheet's policy date; null leaves it blank (the sheet's today). Every live figure rounds
     * by it; a settled or stated figure keeps the one it had.
     */
    @Transactional
    public LocalDate setPolicyDate(String sheetId, String user, LocalDate policyDate) {
        Sheet sheet = sheets.load(sheetId, user);
        Rollup.Figures before = rollup.figures(sheetId);
        sheet.setPolicyDate(policyDate);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, POLICY_DATE, policyDate == null ? "" : policyDate.toString()));
        rollup.changed(sheetId, user, before);
        return policyDate;
    }

    /**
     * Save the sheet's period numbering from; null turns period numbering off. Claims already opened
     * keep the numbers they have.
     */
    @Transactional
    public LocalDate setPeriodNumberingFrom(String sheetId, String user, LocalDate from) {
        Sheet sheet = sheets.load(sheetId, user);
        sheet.setPeriodNumberingFrom(from);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, PERIOD_NUMBERING_FROM, from == null ? "" : from.toString()));
        return from;
    }

    /** Save the sheet's clerk limit; null removes it. */
    @Transactional
    public BigDecimal setClerkLimit(String sheetId, String user, BigDecimal limit) {
        Sheet sheet = sheets.load(sheetId, user);
        BigDecimal scaled = limit == null ? null : limit.setScale(2, RoundingMode.HALF_UP);
        sheet.setClerkLimit(scaled);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, CLERK_LIMIT, scaled == null ? "" : scaled.toPlainString()));
        return scaled;
    }

    /**
     * Save the sheet's floor; null puts back the one a sheet starts with. Every live due moves with it;
     * a settled, stated or closed figure keeps the one it had.
     */
    @Transactional
    public BigDecimal setFloor(String sheetId, String user, BigDecimal floor) {
        Sheet sheet = sheets.load(sheetId, user);
        BigDecimal scaled = floor == null ? Sheet.DEFAULT_FLOOR : floor.setScale(2, RoundingMode.HALF_UP);
        Rollup.Figures before = rollup.figures(sheetId);
        sheet.setFloor(scaled);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, FLOOR, scaled.toPlainString()));
        rollup.changed(sheetId, user, before);
        return scaled;
    }

    /**
     * Whether the user may settle a claim of the given due outright: a steward settles anything, a clerk
     * a due up to the clerk limit (a due of n/a, null, is within any limit). A clerk's settle above it
     * leaves the claim PENDING for a steward.
     */
    @Transactional(readOnly = true)
    public boolean canSettle(String sheetId, String user, BigDecimal due) {
        if (due == null) {
            return true;
        }
        BigDecimal limit = clerkLimit(sheetId);
        return limit == null || due.compareTo(limit) <= 0 || roleOf(sheetId, user) != Role.CLERK;
    }
}
