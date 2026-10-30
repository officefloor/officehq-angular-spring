package net.officefloor.hq.app.access;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.DatedSettings;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetArchived;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.SheetSetting;
import net.officefloor.hq.app.sheet.SheetSettingRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who may do what on a sheet: the role each user name has (clerk unless set to steward) and the
 * clerk limit, the most due a clerk may settle on a claim (none: no limit); and the sheet's floor, the
 * least live due a claim has; its terms, the days a claim has to be paid by from its date, and the terms
 * of each grade that has its own (a grade without uses the sheet's), and the grace days of each grade that
 * has them, the days past a claim's due-by before it is overdue; and its late
 * percent, what an overdue claim's due gains of its figure for every full 30 days overdue; and its policy
 * date, the date a claim's rounding turns on (none: the sheet's today); and its period numbering from,
 * the date from which a claim opened is numbered within its period (none: off); and its cap, the most a
 * person's due-total comes to (none: no cap); and its proof threshold, the most due a claim may have to be
 * settled or approved while it has no proof (none: no threshold), and each grade's own (none: the sheet's). The settings that carry a figure are dated (see
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

    /** The key of a grade's grace days: grace-A, grace-B or grace-C. */
    public static String gradeGraceKey(String grade) {
        return DatedSettings.gradeGraceKey(grade);
    }

    /** The key of a grade's proof threshold: proof-threshold-A, proof-threshold-B or proof-threshold-C. */
    public static String gradeProofThresholdKey(String grade) {
        return DatedSettings.gradeProofThresholdKey(grade);
    }

    public static final String LATE_PCT = DatedSettings.LATE_PCT;

    public static final String POLICY_DATE = "policy-date";

    public static final String PERIOD_NUMBERING_FROM = "period-numbering-from";

    public static final String GRACE_DAYS = "grace-days";

    public static final String ENTRY_DATE = "entry-date";

    public static final String CAP = DatedSettings.CAP;

    public static final String ROUNDING = DatedSettings.ROUNDING;

    public static final String PROOF_THRESHOLD = DatedSettings.PROOF_THRESHOLD;

    public static final String NOTE_THRESHOLD = DatedSettings.NOTE_THRESHOLD;

    public static final String REVIEW_THRESHOLD = DatedSettings.REVIEW_THRESHOLD;

    public static final String POT = DatedSettings.POT;

    public static final String ALERT_THRESHOLD = DatedSettings.ALERT_THRESHOLD;

    public static final String PARENT = DatedSettings.PARENT;

    public static final String FISCAL_START_MONTH = DatedSettings.FISCAL_START_MONTH;

    /** A dated setting to save: its value as written (blank: none, or the starting value) and its from-date (null: the sheet's today). */
    public record Dated(String value, LocalDate from) {
    }

    private final SheetRoleRepository roles;
    private final DelegationRepository delegations;
    private final SheetRepository sheetRows;
    private final SheetSettingRepository settingRows;
    private final SheetService sheets;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public AccessService(SheetRoleRepository roles, DelegationRepository delegations, SheetRepository sheetRows, SheetSettingRepository settingRows,
            SheetService sheets, Rollup rollup, ApplicationEventPublisher events) {
        this.rollup = rollup;
        this.roles = roles;
        this.delegations = delegations;
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

    /**
     * The role the user acts in on the sheet: steward while the sheet's today is inside a delegation to
     * them, otherwise the role set (clerk unless one was set).
     */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return delegator(sheetId, user) != null ? Role.STEWARD : roleSet(sheetId, user);
    }

    /** The role set for the user on the sheet, whatever is delegated to them: clerk unless one was set. */
    private Role roleSet(String sheetId, String user) {
        return roles.findBySheetIdAndUserName(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** The delegations made on the sheet, in the order they were made. */
    @Transactional(readOnly = true)
    public List<Delegation> delegations(String sheetId) {
        return delegations.findBySheetIdOrderById(sheetId);
    }

    /**
     * The steward who delegated the steward role to the user for a range holding the sheet's today (the
     * latest such delegation); null when the user acts in no delegated role now.
     */
    @Transactional(readOnly = true)
    public String delegator(String sheetId, String user) {
        List<Delegation> made = delegations.findBySheetIdAndUserNameOrderByIdDesc(sheetId, user);
        if (made.isEmpty()) {
            return null;
        }
        return delegator(sheetId, user, sheets.today(sheetId));
    }

    /** The steward whose role the user acts in on the given day by a delegation covering it; null when none. */
    public String delegator(String sheetId, String user, LocalDate on) {
        return delegations.findBySheetIdAndUserNameOrderByIdDesc(sheetId, user).stream().filter(d -> d.covers(on))
                .map(Delegation::getDelegatedBy).findFirst().orElse(null);
    }

    /**
     * Delegate the steward role to a user name from a from-date to a to-date, inclusive. Only a steward by
     * the role set (not by a delegation) may delegate, and the to-date may not be before the from-date.
     */
    @Transactional
    public Delegation delegate(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.load(sheetId, user);
        if (roleSet(sheetId, user) != Role.STEWARD) {
            throw new DelegationRefused("Only a steward can delegate the steward role");
        }
        if (to.isBefore(from)) {
            throw new DelegationRefused("The to-date is before the from-date");
        }
        Delegation row = delegations.saveAndFlush(new Delegation(sheetId, name, from, to, user));
        events.publishEvent(new Delegated(sheetId, user, name, from, to));
        return row;
    }

    /** Whether the user is a viewer on the sheet: one who sees everything and can change nothing. */
    @Transactional(readOnly = true)
    public boolean isViewer(String sheetId, String user) {
        return roleOf(sheetId, user) == Role.VIEWER;
    }

    /** Whether the sheet is archived: every change to it is refused, whoever asks. */
    @Transactional(readOnly = true)
    public boolean isArchived(String sheetId) {
        return sheets.isArchived(sheetId);
    }

    /**
     * Archive the sheet. Only a steward may; the sheet then stays visible and every change to it,
     * archiving it again among them, is refused.
     */
    @Transactional
    public Sheet archive(String sheetId, String user) {
        Sheet sheet = sheetRows.lockById(sheetId);
        if (sheet == null) {
            sheet = sheets.load(sheetId, user);
        }
        if (roleOf(sheetId, user) != Role.STEWARD || sheet.isArchived()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can archive the sheet");
        }
        sheet.setArchived(true);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SheetArchived(sheetId, user));
        return sheet;
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

    /** The sheet's entry date as set; null when blank (off). */
    @Transactional(readOnly = true)
    public LocalDate entryDate(String sheetId) {
        return sheets.entryDate(sheetId);
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
            Map<String, Dated> gradeGrace, Dated cap, Dated proofThreshold, Dated noteThreshold, Map<Permission, String> permissions, String entryDate,
            Map<String, Dated> gradeProofThreshold, String pot, String parent, String fiscalStartMonth,
            String alertThreshold, Dated rounding, Dated reviewThreshold) {
        if (fiscalStartMonth != null && !fiscalStartMonth.isEmpty()
                && (Integer.parseInt(fiscalStartMonth) < 1 || Integer.parseInt(fiscalStartMonth) > 12)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The fiscal year start month is 1 to 12");
        }
        if (rounding != null && !rounding.value().isEmpty() && DatedSettings.roundingMode(rounding.value()) == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The rounding mode is half-up or half-even");
        }
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
        if (rounding != null) {
            setDated(sheetId, user, ROUNDING, rounding.value().isEmpty() ? null : rounding.value(), rounding.from(), true);
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
        gradeGrace.forEach((grade, days) -> {
            if (days != null) {
                setDated(sheetId, user, gradeGraceKey(grade), days(days.value(), null), days.from(), true);
            }
        });
        if (cap != null) {
            setDated(sheetId, user, CAP, money(cap.value(), null), cap.from(), true);
        }
        if (proofThreshold != null) {
            setDated(sheetId, user, PROOF_THRESHOLD, money(proofThreshold.value(), null), proofThreshold.from(), false);
        }
        gradeProofThreshold.forEach((grade, threshold) -> {
            if (threshold != null) {
                setDated(sheetId, user, gradeProofThresholdKey(grade), money(threshold.value(), null), threshold.from(), false);
            }
        });
        if (noteThreshold != null) {
            setDated(sheetId, user, NOTE_THRESHOLD, money(noteThreshold.value(), null), noteThreshold.from(), false);
        }
        if (reviewThreshold != null) {
            setDated(sheetId, user, REVIEW_THRESHOLD, money(reviewThreshold.value(), null), reviewThreshold.from(), false);
        }
        permissions.forEach((permission, roles) -> {
            if (roles != null) {
                setPermission(sheetId, user, permission, roles.trim());
            }
        });
        if (entryDate != null) {
            setEntryDate(sheetId, user, entryDate.isEmpty() ? null : LocalDate.parse(entryDate));
        }
        if (pot != null) {
            setPot(sheetId, user, money(pot, null));
        }
        if (alertThreshold != null) {
            setAlertThreshold(sheetId, user, money(alertThreshold, null));
        }
        if (parent != null) {
            setParent(sheetId, user, parent.isEmpty() ? null : parent);
        }
        if (fiscalStartMonth != null) {
            setFiscalStartMonth(sheetId, user, fiscalStartMonth.isEmpty()
                    ? DatedSettings.DEFAULT_FISCAL_START_MONTH : Integer.parseInt(fiscalStartMonth));
        }
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

    /**
     * Record the sheet's pot, two decimals (null: none). Not dated: the value holds from the beginning of time,
     * replacing the one it had.
     */
    @Transactional
    public void setPot(String sheetId, String user, String pot) {
        setUndated(sheetId, user, POT, pot);
    }

    /**
     * Record the sheet's alert threshold, two decimals (null: off). Not dated: the value holds from the beginning
     * of time, replacing the one it had.
     */
    @Transactional
    public void setAlertThreshold(String sheetId, String user, String threshold) {
        setUndated(sheetId, user, ALERT_THRESHOLD, threshold);
    }

    /** Record an undated setting as written (null: none), replacing the one it had. */
    private void setUndated(String sheetId, String user, String key, String value) {
        sheets.load(sheetId, user);
        SheetSetting row = settingRows.findBySheetIdAndKeyAndFromDate(sheetId, key, null).orElse(null);
        if (row == null) {
            settingRows.saveAndFlush(new SheetSetting(sheetId, key, null, value));
        } else {
            row.setValue(value);
            settingRows.flush();
        }
        events.publishEvent(new SettingChanged(sheetId, user, key, value == null ? "" : value, null));
    }

    /**
     * Name the sheet's parent sheet (null: none), whose rates a grade with none in force on this sheet takes.
     * Not dated: the value holds from the beginning of time, replacing the one it had. Refused (409, changing
     * nothing) when it is this sheet, a sheet never loaded, or a sheet that has this sheet as an ancestor.
     */
    @Transactional
    public void setParent(String sheetId, String user, String parent) {
        sheets.load(sheetId, user);
        if (parent != null) {
            if (parent.equals(sheetId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A sheet cannot be its own parent");
            }
            if (sheetRows.findById(parent).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "No such sheet " + parent);
            }
            Set<String> seen = new HashSet<>();
            for (String up = parent; up != null && seen.add(up); up = sheets.settings(up).parent()) {
                if (up.equals(sheetId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet is an ancestor of " + parent);
                }
            }
        }
        Rollup.Figures before = rollup.figures(sheetId);
        SheetSetting row = settingRows.findBySheetIdAndKeyAndFromDate(sheetId, PARENT, null).orElse(null);
        if (row == null) {
            settingRows.saveAndFlush(new SheetSetting(sheetId, PARENT, null, parent));
        } else {
            row.setValue(parent);
            settingRows.flush();
        }
        events.publishEvent(new SettingChanged(sheetId, user, PARENT, parent == null ? "" : parent, null));
        rollup.changed(sheetId, user, before);
    }

    /**
     * Record the month (1 to 12) the sheet's fiscal year starts in. Not dated: the value holds from the beginning
     * of time, replacing the one it had. Refused (409, changing nothing) for any other month.
     */
    @Transactional
    public void setFiscalStartMonth(String sheetId, String user, int month) {
        if (month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The fiscal year start month is 1 to 12");
        }
        sheets.load(sheetId, user);
        String value = Integer.toString(month);
        SheetSetting row = settingRows.findBySheetIdAndKeyAndFromDate(sheetId, FISCAL_START_MONTH, null).orElse(null);
        if (row == null) {
            settingRows.saveAndFlush(new SheetSetting(sheetId, FISCAL_START_MONTH, null, value));
        } else {
            row.setValue(value);
            settingRows.flush();
        }
        events.publishEvent(new SettingChanged(sheetId, user, FISCAL_START_MONTH, value, null));
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
     * Save the sheet's entry date; null turns it off. Not dated; while set, a new claim given no date takes
     * it in place of the sheet's today. Claims already added keep the dates they have.
     */
    @Transactional
    public LocalDate setEntryDate(String sheetId, String user, LocalDate entryDate) {
        Sheet sheet = sheets.load(sheetId, user);
        sheet.setEntryDate(entryDate);
        sheetRows.saveAndFlush(sheet);
        events.publishEvent(new SettingChanged(sheetId, user, ENTRY_DATE, entryDate == null ? "" : entryDate.toString(), null));
        return entryDate;
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
