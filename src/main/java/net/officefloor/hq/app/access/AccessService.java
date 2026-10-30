package net.officefloor.hq.app.access;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.DatedSettings;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.SheetSetting;
import net.officefloor.hq.app.sheet.SheetSettingRepository;
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
 * the date from which a claim opened is numbered within its period (none: off); and its cap, the most a
 * person's due-total comes to (none: no cap); and its proof threshold, the most due a claim may have to be
 * settled or approved while it has no proof (none: no threshold). The settings that carry a figure are dated (see
 * {@link DatedSettings}): a save records its value from a from-date, the sheet's today when none is given.
 */
@Service
public class AccessService {

    public static final String CLERK_LIMIT = DatedSettings.CLERK_LIMIT;

    public static final String FLOOR = DatedSettings.FLOOR;

    public static final String TERMS = DatedSettings.TERMS;

    /** The key of a grade's own terms: terms-A, terms-B or terms-C. */
    public static String gradeTermsKey(String grade) {
        return DatedSettings.gradeTermsKey(grade);
    }

    public static final String LATE_PCT = DatedSettings.LATE_PCT;

    public static final String POLICY_DATE = "policy-date";

    public static final String PERIOD_NUMBERING_FROM = "period-numbering-from";

    public static final String GRACE_DAYS = "grace-days";

    public static final String CAP = DatedSettings.CAP;

    public static final String PROOF_THRESHOLD = DatedSettings.PROOF_THRESHOLD;

    /** A dated setting to save: its value as written (blank: none, or the starting value) and its from-date (null: the sheet's today). */
    public record Dated(String value, LocalDate from) {
    }

    private final SheetRoleRepository roles;
    private final SheetRepository sheetRows;
    private final SheetSettingRepository settingRows;
    private final SheetService sheets;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public AccessService(SheetRoleRepository roles, SheetRepository sheetRows, SheetSettingRepository settingRows,
            SheetService sheets, Rollup rollup, ApplicationEventPublisher events) {
        this.rollup = rollup;
        this.roles = roles;
        this.sheetRows = sheetRows;
        this.settingRows = settingRows;
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

    /** Whether the user is a viewer on the sheet: one who sees everything and can change nothing. */
    @Transactional(readOnly = true)
    public boolean isViewer(String sheetId, String user) {
        return roleOf(sheetId, user) == Role.VIEWER;
    }

    /** The sheet's dated settings, with the sheet's today to read the values in force now. */
    @Transactional(readOnly = true)
    public DatedSettings settings(String sheetId) {
        return sheets.settings(sheetId);
    }

    /** The sheet's today. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
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

    /** The sheet's grace days as set; null when blank (off). */
    @Transactional(readOnly = true)
    public Integer graceDays(String sheetId) {
        return sheets.graceDays(sheetId);
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
     * out (null) is kept as it is. A dated one is saved from its from-date.
     */
    @Transactional
    public void setSettings(String sheetId, String user, Dated clerkLimit, Dated floor, Dated terms,
            Map<String, Dated> gradeTerms, Dated latePct, String policyDate, String periodNumberingFrom, String graceDays,
            Dated cap, Dated proofThreshold, Map<Permission, String> permissions) {
        if (clerkLimit != null) {
            setDated(sheetId, user, CLERK_LIMIT, money(clerkLimit.value(), null), clerkLimit.from(), false);
        }
        if (floor != null) {
            setDated(sheetId, user, FLOOR, money(floor.value(), Sheet.DEFAULT_FLOOR), floor.from(), true);
        }
        if (terms != null) {
            setDated(sheetId, user, TERMS, days(terms.value(), Sheet.DEFAULT_TERMS), terms.from(), false);
        }
        gradeTerms.forEach((grade, days) -> {
            if (days != null) {
                setDated(sheetId, user, gradeTermsKey(grade), days(days.value(), null), days.from(), false);
            }
        });
        if (latePct != null) {
            setDated(sheetId, user, LATE_PCT, money(latePct.value(), Sheet.DEFAULT_LATE_PCT), latePct.from(), true);
        }
        if (policyDate != null) {
            setPolicyDate(sheetId, user, policyDate.isEmpty() ? null : LocalDate.parse(policyDate));
        }
        if (periodNumberingFrom != null) {
            setPeriodNumberingFrom(sheetId, user,
                    periodNumberingFrom.isEmpty() ? null : LocalDate.parse(periodNumberingFrom));
        }
        if (graceDays != null) {
            setGraceDays(sheetId, user, graceDays.isEmpty() ? null : Integer.valueOf(graceDays));
        }
        if (cap != null) {
            setDated(sheetId, user, CAP, money(cap.value(), null), cap.from(), true);
        }
        if (proofThreshold != null) {
            setDated(sheetId, user, PROOF_THRESHOLD, money(proofThreshold.value(), null), proofThreshold.from(), false);
        }
        permissions.forEach((permission, roles) -> {
            if (roles != null) {
                setPermission(sheetId, user, permission, roles.trim());
            }
        });
    }

    /** The roles that may take the action as set (comma separated); null when blank (the standing rule). */
    @Transactional(readOnly = true)
    public String permission(String sheetId, Permission permission) {
        return sheets.settings(sheetId).on(permission.key(), sheets.today(sheetId)).orElse(null);
    }

    /** Whether the user's role on the sheet may take the action under the sheet's permission matrix. */
    @Transactional(readOnly = true)
    public boolean allows(String sheetId, String user, Permission permission) {
        return permission.allows(permission(sheetId, permission), roleOf(sheetId, user));
    }

    /**
     * Record which roles may take an action, as written (blank: the standing rule). The permission matrix is
     * not dated: the value holds from the beginning of time, replacing the one it had.
     */
    @Transactional
    public void setPermission(String sheetId, String user, Permission permission, String roles) {
        sheets.load(sheetId, user);
        String value = roles.isEmpty() ? null : roles;
        SheetSetting row = settingRows.findBySheetIdAndKeyAndFromDate(sheetId, permission.key(), null).orElse(null);
        if (row == null) {
            settingRows.saveAndFlush(new SheetSetting(sheetId, permission.key(), null, value));
        } else {
            row.setValue(value);
            settingRows.flush();
        }
        events.publishEvent(new SettingChanged(sheetId, user, permission.key(), roles, null));
    }

    /** A figure as kept: two decimals; blank is the starting value given (null: none). */
    private static String money(String value, BigDecimal start) {
        BigDecimal figure = value.isEmpty() ? start : new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
        return figure == null ? null : figure.toPlainString();
    }

    /** A number of days as kept; blank is the starting value given (null: none). */
    private static String days(String value, Integer start) {
        Integer days = value.isEmpty() ? start : Integer.valueOf(value);
        return days == null ? null : days.toString();
    }

    /**
     * Record a dated setting's value (null: none) from the from-date (null: the sheet's today), replacing
     * the value it had from that date and keeping the others. When the setting moves live figures (the
     * floor, the late percent, the cap), every figure it moves is announced. The terms fix the due-by of a
     * claim when it is added; a claim already added keeps the due-by it has.
     */
    @Transactional
    public void setDated(String sheetId, String user, String key, String value, LocalDate from, boolean moves) {
        Sheet sheet = sheets.load(sheetId, user);
        LocalDate on = from == null ? sheet.getToday() : from;
        Rollup.Figures before = moves ? rollup.figures(sheetId) : null;
        SheetSetting row = settingRows.findBySheetIdAndKeyAndFromDate(sheetId, key, on).orElse(null);
        if (row == null) {
            settingRows.saveAndFlush(new SheetSetting(sheetId, key, on, value));
        } else {
            row.setValue(value);
            settingRows.flush();
        }
        events.publishEvent(new SettingChanged(sheetId, user, key, value == null ? "" : value, from));
        if (moves) {
            rollup.changed(sheetId, user, before);
        }
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
        events.publishEvent(new SettingChanged(sheetId, user, POLICY_DATE, policyDate == null ? "" : policyDate.toString(), null));
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
        events.publishEvent(new SettingChanged(sheetId, user, PERIOD_NUMBERING_FROM, from == null ? "" : from.toString(), null));
        return from;
    }

    /**
     * Save the sheet's grace days; null turns them off. Not dated; it takes effect at the next change of
     * the sheet's today.
     */
    @Transactional
    public Integer setGraceDays(String sheetId, String user, Integer days) {
        Sheet sheet = sheets.load(sheetId, user);
        sheet.setGraceDays(days);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, GRACE_DAYS, days == null ? "" : days.toString(), null));
        return days;
    }

    /**
     * Whether the user may settle a claim of the given due outright: a steward settles anything, a clerk
     * a due up to the clerk limit in force on the sheet's today (a due of n/a, null, is within any limit). A clerk's settle above it
     * leaves the claim PENDING for a steward.
     */
    @Transactional(readOnly = true)
    public boolean canSettle(String sheetId, String user, BigDecimal due) {
        if (due == null) {
            return true;
        }
        BigDecimal limit = sheets.settings(sheetId).clerkLimit(sheets.today(sheetId));
        return limit == null || due.compareTo(limit) <= 0 || roleOf(sheetId, user) != Role.CLERK;
    }
}
