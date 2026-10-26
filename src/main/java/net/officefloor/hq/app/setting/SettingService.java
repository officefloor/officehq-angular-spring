package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.officefloor.hq.app.ActingFor;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The sheet's settings (such as the clerk limit), the roles it assigns user names, and the stewards'
 *  delegations of their role. */
@Service
public class SettingService implements ActingFor {

    /** The key of the clerk limit: the highest due a clerk may settle; no value means no limit. */
    public static final String CLERK_LIMIT = "clerk-limit";

    /** The key of the floor: the least a claim's live due can be; the sheet starts at {@link #DEFAULT_FLOOR}. */
    public static final String FLOOR = "floor";

    /** The floor of a sheet that has not set one. */
    public static final BigDecimal DEFAULT_FLOOR = new BigDecimal("1.00");

    /** The key of the terms: the business days after a claim's date it is due by; the sheet starts at {@link #DEFAULT_TERMS}.
     *  A grade may set its own under terms-<grade>; a grade without them uses the sheet's. */
    public static final String TERMS = "terms";

    /** The terms of a sheet that has not set them, in business days. */
    public static final int DEFAULT_TERMS = 14;

    /** The prefix of a grade's grace days, set under grace-<grade>: a claim whose person's grade in force on
     *  its date has grace days in force on that date is overdue only once its due-by plus them is before the
     *  sheet's today, and its late charge counts from then; no value means none. Dated. */
    public static final String GRACE = "grace";

    /** The key of the late percent: what an overdue claim gains of its figure for every full 30 days
     *  overdue; the sheet starts at {@link #DEFAULT_LATE_PCT}. */
    public static final String LATE_PCT = "late-pct";

    /** The late percent of a sheet that has not set one. */
    public static final BigDecimal DEFAULT_LATE_PCT = new BigDecimal("0.00");

    /** The key of the rounding mode: half-up or half-even, how a claim dated while it is in force rounds
     *  its live figures; with no value the policy date decides. */
    public static final String ROUNDING = "rounding";

    /** The key of the policy date: a claim dated before it rounds half up, one dated on or after it half
     *  to even; with no value the policy date is the sheet's today. */
    public static final String POLICY_DATE = "policy-date";

    /** The key of period numbering from: while the sheet's today is on or after it, a claim opened is
     *  numbered within its period; with no value numbering is sheet-wide. */
    public static final String PERIOD_NUMBERING_FROM = "period-numbering-from";

    /** The key of the cap: the most a person's due-total can be; no value means no cap. */
    public static final String CAP = "cap";

    /** The key of the proof threshold: a claim whose due is above it cannot be settled or approved
     *  without a proof; no value means none. A grade may set its own under proof-threshold-<grade>, dated;
     *  a claim whose person's grade on its date has none in force then uses the sheet's. */
    public static final String PROOF_THRESHOLD = "proof-threshold";

    /** The key of the note threshold: a claim whose due is above it cannot be opened with an empty note;
     *  no value means none. */
    public static final String NOTE_THRESHOLD = "note-threshold";

    /** The key of the review threshold: a claim whose due is above the one in force on its date goes to
     *  REVIEW when opened, for a steward to review; no value means none. Dated. */
    public static final String REVIEW_THRESHOLD = "review-threshold";

    /** The key of the grace days: once the sheet's today is changed to after an open period's to-date plus
     *  these days, the period is closed; no value means off. Not dated. */
    public static final String GRACE_DAYS = "grace-days";

    /** The key of the fiscal year start month: the month (1 to 12) each fiscal year starts on its first day;
     *  no value means 1 (January). Not dated. */
    public static final String FISCAL_START_MONTH = "fiscal-start-month";

    /** The fiscal year start month while none is set. */
    public static final int DEFAULT_FISCAL_START_MONTH = 1;

    /** The key of the entry date: while it is set, a new claim given no date is dated it instead of the
     *  sheet's today; no value means off. Not dated. */
    public static final String ENTRY_DATE = "entry-date";

    /** The key of the pot: the amount each balanced pool shares out among its members by their shares; no
     *  value means none. Not dated. */
    public static final String POT = "pot";

    /** The key of the alert threshold: a change that takes a person's due-total from at or below it (or from
     *  none) to above it audits an ALERT for them; no value means off. Not dated. */
    public static final String ALERT_THRESHOLD = "alert-threshold";

    /** The key of the parent: the sheet whose rates (then its parent's, up to {@link #PARENT_LEVELS} levels up)
     *  are used for a grade and date that have none in force on this sheet; no value means none. Not dated. */
    public static final String PARENT = "parent";

    /** How many levels up the sheet's parents are looked to for a rate. */
    public static final int PARENT_LEVELS = 3;

    /** The actions the sheet's permission matrix names, each set under perm-<action>. */
    public static final List<String> PERMISSION_ACTIONS = List.of("open", "settle", "void", "reassign", "close", "reopen");

    /** The roles that may take an action whose permission is blank: the standing rule. */
    private static final Map<String, Set<Role>> STANDING_PERMISSIONS = Map.of(
            "open", EnumSet.of(Role.CLERK, Role.STEWARD), "settle", EnumSet.of(Role.CLERK, Role.STEWARD),
            "void", EnumSet.of(Role.CLERK, Role.STEWARD), "close", EnumSet.of(Role.CLERK, Role.STEWARD),
            "reassign", EnumSet.of(Role.STEWARD), "reopen", EnumSet.of(Role.STEWARD));

    private final SheetSettingRepository settings;
    private final SheetSettingDatedRepository dated;
    private final SettingChangeRepository changes;
    private final SheetRoleRepository roles;
    private final DelegationRepository delegations;
    private final SheetParentRepository parents;
    private final SheetService sheets;
    private final Rollup rollup;
    private final Audit audit;

    public SettingService(SheetSettingRepository settings, SheetSettingDatedRepository dated,
            SettingChangeRepository changes, SheetRoleRepository roles, DelegationRepository delegations,
            SheetParentRepository parents, SheetService sheets, Rollup rollup, Audit audit) {
        this.settings = settings;
        this.dated = dated;
        this.changes = changes;
        this.roles = roles;
        this.delegations = delegations;
        this.parents = parents;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's dated settings as the store holds them now. */
    @Transactional(readOnly = true)
    public DatedSettings dated(String sheetId) {
        return DatedSettings.of(dated, sheetId);
    }

    /** The sheet's dated settings as they stood at the end of a date: every value recorded while the
     *  sheet's today was on or before it counts, every later one does not. */
    @Transactional(readOnly = true)
    public DatedSettings datedAsOf(String sheetId, LocalDate asOf) {
        Map<String, Map<LocalDate, String>> byKey = new TreeMap<>();
        for (SettingChange change : changes.findBySheetIdOrderByIdAsc(sheetId)) {
            if (change.madeBy(asOf)) {
                byKey.computeIfAbsent(change.getKey(), k -> new TreeMap<>()).put(change.getFrom(), change.getValue());
            }
        }
        List<SheetSettingDated> then = new ArrayList<>();
        byKey.forEach((key, values) -> values.forEach((from, value) ->
                then.add(new SheetSettingDated(sheetId, key, from, value))));
        return DatedSettings.of(then);
    }

    /** The sheet's today. */
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    /** The floor in force on the date, with two decimals. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId, LocalDate date) {
        return dated(sheetId).floorOn(date);
    }

    /** Record the sheet's floor, kept to two decimals, from a date (blank: the sheet's today); the live
     *  dues it moves are audited after it. */
    @Transactional
    public BigDecimal setFloor(String sheetId, String user, BigDecimal floor, LocalDate from) {
        BigDecimal value = floor.setScale(2);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        record(sheetId, user, FLOOR, value.toPlainString(), from);
        rollup.recordChanges(user, before);
        return value;
    }

    /** Record the sheet's rounding mode (half-up or half-even) from a date (blank: the sheet's today); blank
     *  clears it from then, leaving the policy date to decide; anything else is refused. The live dues it
     *  moves are audited after it. */
    @Transactional
    public String setRounding(String sheetId, String user, String mode, LocalDate from) {
        String value = mode == null ? "" : mode.trim();
        if (!value.isEmpty() && Rounding.mode(value) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The rounding mode is half-up or half-even.");
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        record(sheetId, user, ROUNDING, value, from);
        rollup.recordChanges(user, before);
        return value;
    }

    /** The key of a grade's own terms. */
    public static String termsKey(Grade grade) {
        return TERMS + "-" + grade.name();
    }

    /** The terms of a grade in force on the date, in days: its own, else the sheet's (a person with no
     *  grade has the sheet's). */
    @Transactional(readOnly = true)
    public int terms(String sheetId, Grade grade, LocalDate date) {
        return dated(sheetId).termsOn(grade, date);
    }

    /** Record the sheet's terms, in days, from a date. A claim's due-by is fixed when it is added, so no claim moves. */
    @Transactional
    public int setTerms(String sheetId, String user, int days, LocalDate from) {
        record(sheetId, user, TERMS, Integer.toString(days), from);
        return days;
    }

    /** Record a grade's own terms, in days, from a date; null clears them from then, leaving the sheet's
     *  terms. No claim moves. */
    @Transactional
    public Integer setGradeTerms(String sheetId, String user, Grade grade, Integer days, LocalDate from) {
        record(sheetId, user, termsKey(grade), days == null ? "" : days.toString(), from);
        return days;
    }

    /** The key of a grade's grace days. */
    public static String graceKey(Grade grade) {
        return GRACE + "-" + grade.name();
    }

    /** Record a grade's grace days from a date; null clears them from then. The late charges and overdue
     *  claims it moves are audited after it. */
    @Transactional
    public Integer setGradeGrace(String sheetId, String user, Grade grade, Integer days, LocalDate from) {
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        record(sheetId, user, graceKey(grade), days == null ? "" : days.toString(), from);
        rollup.recordChanges(user, before);
        return days;
    }

    /** Record the sheet's late percent, kept to two decimals, from a date; the late charges it moves are
     *  audited after it. */
    @Transactional
    public BigDecimal setLatePct(String sheetId, String user, BigDecimal pct, LocalDate from) {
        BigDecimal value = pct.setScale(2);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        record(sheetId, user, LATE_PCT, value.toPlainString(), from);
        rollup.recordChanges(user, before);
        return value;
    }

    /** The late percent in force on the date, with two decimals. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId, LocalDate date) {
        return dated(sheetId).latePctOn(date);
    }

    /** Record a dated setting's value (blank: cleared) from a date, or from the sheet's today when none is
     *  given; a value already recorded from that date is replaced. The audit line carries the from-date
     *  when one was given. */
    private void record(String sheetId, String user, String key, String value, LocalDate from) {
        sheets.load(sheetId);
        LocalDate on = from != null ? from : sheets.today(sheetId);
        SheetSettingDated setting = dated.findBySheetIdAndKeyAndFrom(sheetId, key, on).orElse(null);
        try {
            if (setting == null) {
                dated.saveAndFlush(new SheetSettingDated(sheetId, key, on, value));
            } else {
                setting.setValue(value);
                dated.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        changes.saveAndFlush(new SettingChange(sheetId, key, on, value, sheets.today(sheetId)));
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + key
                + " value=" + value + (from == null ? "" : " from=" + from));
    }

    /** The sheet's policy date as it set it, or null when it is blank (the sheet's today then). */
    @Transactional(readOnly = true)
    public LocalDate policyDate(String sheetId) {
        return policyDateOf(settings, sheetId);
    }

    /** The sheet's policy date as the store holds it, within the caller's transaction; null when blank. */
    public static LocalDate policyDateOf(SheetSettingRepository settings, String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, POLICY_DATE).map((s) -> LocalDate.parse(s.getValue()))
                .orElse(null);
    }

    /** Set the sheet's policy date; null clears it, leaving the sheet's today. The live dues it moves are
     *  audited after it. */
    @Transactional
    public LocalDate setPolicyDate(String sheetId, String user, LocalDate date) {
        sheets.load(sheetId);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, POLICY_DATE).orElse(null);
        try {
            if (date == null) {
                if (setting != null) {
                    settings.delete(setting);
                    settings.flush();
                }
            } else if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, POLICY_DATE, date.toString()));
            } else {
                setting.setValue(date.toString());
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + POLICY_DATE
                + " value=" + (date == null ? "" : date.toString()));
        rollup.recordChanges(user, before);
        return date;
    }

    /** The date the sheet numbers claims within their periods from, or null when it is blank (off). */
    @Transactional(readOnly = true)
    public LocalDate periodNumberingFrom(String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, PERIOD_NUMBERING_FROM).map((s) -> LocalDate.parse(s.getValue()))
                .orElse(null);
    }

    /** Set the date the sheet numbers claims within their periods from; null clears it, leaving
     *  sheet-wide numbering. Claims already numbered keep their numbers. */
    @Transactional
    public LocalDate setPeriodNumberingFrom(String sheetId, String user, LocalDate date) {
        sheets.load(sheetId);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, PERIOD_NUMBERING_FROM).orElse(null);
        try {
            if (date == null) {
                if (setting != null) {
                    settings.delete(setting);
                    settings.flush();
                }
            } else if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, PERIOD_NUMBERING_FROM, date.toString()));
            } else {
                setting.setValue(date.toString());
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + PERIOD_NUMBERING_FROM
                + " value=" + (date == null ? "" : date.toString()));
        return date;
    }

    /** The sheet's entry date, or null when it is blank (off: a new claim is dated the sheet's today). */
    @Transactional(readOnly = true)
    public LocalDate entryDate(String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, ENTRY_DATE).map((s) -> LocalDate.parse(s.getValue()))
                .orElse(null);
    }

    /** Set the sheet's entry date; null clears it, so a new claim given no date is dated the sheet's today.
     *  Claims already added keep their dates. */
    @Transactional
    public LocalDate setEntryDate(String sheetId, String user, LocalDate date) {
        sheets.load(sheetId);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, ENTRY_DATE).orElse(null);
        try {
            if (date == null) {
                if (setting != null) {
                    settings.delete(setting);
                    settings.flush();
                }
            } else if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, ENTRY_DATE, date.toString()));
            } else {
                setting.setValue(date.toString());
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + ENTRY_DATE
                + " value=" + (date == null ? "" : date.toString()));
        return date;
    }

    /** The sheet's grace days, or null when it is blank (off). */
    @Transactional(readOnly = true)
    public Integer graceDays(String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, GRACE_DAYS).map((s) -> Integer.valueOf(s.getValue()))
                .orElse(null);
    }

    /** Set the sheet's grace days; null clears them, turning the closing of periods off. */
    @Transactional
    public Integer setGraceDays(String sheetId, String user, Integer days) {
        sheets.load(sheetId);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, GRACE_DAYS).orElse(null);
        try {
            if (days == null) {
                if (setting != null) {
                    settings.delete(setting);
                    settings.flush();
                }
            } else if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, GRACE_DAYS, days.toString()));
            } else {
                setting.setValue(days.toString());
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + GRACE_DAYS
                + " value=" + (days == null ? "" : days.toString()));
        return days;
    }

    /** The sheet's fiscal year start month, 1 to 12; 1 when none is set. */
    @Transactional(readOnly = true)
    public int fiscalStartMonth(String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, FISCAL_START_MONTH).map((s) -> Integer.valueOf(s.getValue()))
                .orElse(DEFAULT_FISCAL_START_MONTH);
    }

    /** Set the sheet's fiscal year start month; refused unless it is 1 to 12. */
    @Transactional
    public int setFiscalStartMonth(String sheetId, String user, int month) {
        if (month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The fiscal year start month is 1 to 12.");
        }
        sheets.load(sheetId);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, FISCAL_START_MONTH).orElse(null);
        try {
            if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, FISCAL_START_MONTH, Integer.toString(month)));
            } else {
                setting.setValue(Integer.toString(month));
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + FISCAL_START_MONTH
                + " value=" + month);
        return month;
    }

    /** The sheet's pot, with two decimals, or null when it is blank (none). */
    @Transactional(readOnly = true)
    public BigDecimal pot(String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, POT).map((s) -> new BigDecimal(s.getValue()).setScale(2))
                .orElse(null);
    }

    /** Set the sheet's pot, kept to two decimals; null clears it, leaving no pot. */
    @Transactional
    public BigDecimal setPot(String sheetId, String user, BigDecimal pot) {
        BigDecimal value = pot == null ? null : pot.setScale(2);
        sheets.load(sheetId);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, POT).orElse(null);
        try {
            if (value == null) {
                if (setting != null) {
                    settings.delete(setting);
                    settings.flush();
                }
            } else if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, POT, value.toPlainString()));
            } else {
                setting.setValue(value.toPlainString());
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + POT
                + " value=" + (value == null ? "" : value.toPlainString()));
        return value;
    }

    /** The sheet's alert threshold, with two decimals, or null when it is blank (off). */
    @Transactional(readOnly = true)
    public BigDecimal alertThreshold(String sheetId) {
        return alertThresholdOf(settings, sheetId);
    }

    /** The sheet's alert threshold as the store holds it, within the caller's transaction; null when off. */
    public static BigDecimal alertThresholdOf(SheetSettingRepository settings, String sheetId) {
        return settings.findBySheetIdAndKey(sheetId, ALERT_THRESHOLD).map((s) -> new BigDecimal(s.getValue()).setScale(2))
                .orElse(null);
    }

    /** Set the sheet's alert threshold, kept to two decimals; null clears it, turning alerts off. */
    @Transactional
    public BigDecimal setAlertThreshold(String sheetId, String user, BigDecimal threshold) {
        BigDecimal value = threshold == null ? null : threshold.setScale(2);
        sheets.load(sheetId);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, ALERT_THRESHOLD).orElse(null);
        try {
            if (value == null) {
                if (setting != null) {
                    settings.delete(setting);
                    settings.flush();
                }
            } else if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, ALERT_THRESHOLD, value.toPlainString()));
            } else {
                setting.setValue(value.toPlainString());
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + ALERT_THRESHOLD
                + " value=" + (value == null ? "" : value.toPlainString()));
        return value;
    }

    /** The sheet's parent, or null when it has none. */
    @Transactional(readOnly = true)
    public String parent(String sheetId) {
        return parentOf(parents, sheetId);
    }

    /** The sheet's parent as the store holds it, within the caller's transaction; null when it has none. */
    public static String parentOf(SheetParentRepository parents, String sheetId) {
        return parents.findById(sheetId).map(SheetParentLink::getParentId).orElse(null);
    }

    /** The sheets a rate is looked for in, nearest first: the sheet itself, then its parent, and so on up to
     *  {@link #PARENT_LEVELS} levels up; within the caller's transaction. */
    public static List<String> lineageOf(SheetParentRepository parents, String sheetId) {
        List<String> lineage = new ArrayList<>(List.of(sheetId));
        String at = sheetId;
        for (int level = 0; level < PARENT_LEVELS; level++) {
            at = parentOf(parents, at);
            if (at == null || lineage.contains(at)) break;
            lineage.add(at);
        }
        return lineage;
    }

    /** Set the sheet's parent; blank clears it. Refused when it is the sheet itself, a sheet that has never
     *  been loaded, or a sheet that has this sheet as an ancestor. The live dues it moves are audited after it. */
    @Transactional
    public String setParent(String sheetId, String user, String parent) {
        String value = parent == null || parent.isBlank() ? null : parent.trim();
        if (value != null) {
            if (value.equals(sheetId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A sheet cannot be its own parent.");
            }
            if (!sheets.exists(value)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The parent sheet has never been loaded.");
            }
            List<String> seen = new ArrayList<>();
            for (String at = value; at != null && !seen.contains(at); at = parentOf(parents, at)) {
                if (at.equals(sheetId)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "The parent sheet has this sheet as an ancestor.");
                }
                seen.add(at);
            }
        }
        sheets.load(sheetId);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        SheetParentLink link = parents.findById(sheetId).orElse(null);
        try {
            if (value == null) {
                if (link != null) {
                    parents.delete(link);
                    parents.flush();
                }
            } else if (link == null) {
                parents.saveAndFlush(new SheetParentLink(sheetId, value));
            } else {
                link.setParentId(value);
                parents.saveAndFlush(link);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + PARENT
                + " value=" + (value == null ? "" : value));
        rollup.recordChanges(user, before);
        return value;
    }

    /** The cap in force on the sheet's today, with two decimals, or null when there is no cap. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String sheetId) {
        return dated(sheetId).capOn(sheets.today(sheetId));
    }

    /** A person's due-total held to the cap: the cap when it is above it; n/a (null) and no cap leave it as it is. */
    public static BigDecimal capped(BigDecimal dueTotal, BigDecimal cap) {
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
    }

    /** Record the sheet's cap, kept to two decimals, from a date; null clears it from then, leaving no
     *  cap. The due-totals it moves are audited after it. */
    @Transactional
    public BigDecimal setCap(String sheetId, String user, BigDecimal cap, LocalDate from) {
        BigDecimal value = cap == null ? null : cap.setScale(2);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        record(sheetId, user, CAP, value == null ? "" : value.toPlainString(), from);
        rollup.recordChanges(user, before);
        return value;
    }

    /** The clerk limit in force on the sheet's today, with two decimals, or null when there is no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return dated(sheetId).clerkLimitOn(sheets.today(sheetId));
    }

    /** Record the sheet's clerk limit, kept to two decimals, from a date; null clears it from then,
     *  leaving no limit. */
    @Transactional
    public BigDecimal setClerkLimit(String sheetId, String user, BigDecimal limit, LocalDate from) {
        BigDecimal value = limit == null ? null : limit.setScale(2);
        record(sheetId, user, CLERK_LIMIT, value == null ? "" : value.toPlainString(), from);
        return value;
    }

    /** The proof threshold in force on the date, with two decimals, or null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThreshold(String sheetId, LocalDate date) {
        return dated(sheetId).proofThresholdOn(date);
    }

    /** The proof threshold of a grade in force on the date: its own when set then, else the sheet's; null
     *  when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThreshold(String sheetId, Grade grade, LocalDate date) {
        return dated(sheetId).proofThresholdOn(grade, date);
    }

    /** The key of a grade's own proof threshold. */
    public static String proofThresholdKey(Grade grade) {
        return PROOF_THRESHOLD + "-" + grade.name();
    }

    /** Record a grade's own proof threshold, kept to two decimals, from a date; null clears it from then,
     *  leaving the sheet's. */
    @Transactional
    public BigDecimal setGradeProofThreshold(String sheetId, String user, Grade grade, BigDecimal threshold,
            LocalDate from) {
        BigDecimal value = threshold == null ? null : threshold.setScale(2);
        record(sheetId, user, proofThresholdKey(grade), value == null ? "" : value.toPlainString(), from);
        return value;
    }

    /** Record the sheet's proof threshold, kept to two decimals, from a date; null clears it from then. */
    @Transactional
    public BigDecimal setProofThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        BigDecimal value = threshold == null ? null : threshold.setScale(2);
        record(sheetId, user, PROOF_THRESHOLD, value == null ? "" : value.toPlainString(), from);
        return value;
    }

    /** The note threshold in force on the date, with two decimals, or null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal noteThreshold(String sheetId, LocalDate date) {
        return dated(sheetId).noteThresholdOn(date);
    }

    /** Record the sheet's note threshold, kept to two decimals, from a date; null clears it from then. */
    @Transactional
    public BigDecimal setNoteThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        BigDecimal value = threshold == null ? null : threshold.setScale(2);
        record(sheetId, user, NOTE_THRESHOLD, value == null ? "" : value.toPlainString(), from);
        return value;
    }

    /** The review threshold in force on the date, with two decimals, or null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal reviewThreshold(String sheetId, LocalDate date) {
        return dated(sheetId).reviewThresholdOn(date);
    }

    /** Record the sheet's review threshold, kept to two decimals, from a date; null clears it from then. */
    @Transactional
    public BigDecimal setReviewThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        BigDecimal value = threshold == null ? null : threshold.setScale(2);
        record(sheetId, user, REVIEW_THRESHOLD, value == null ? "" : value.toPlainString(), from);
        return value;
    }

    /** The key of an action's permission. */
    public static String permissionKey(String action) {
        return "perm-" + action;
    }

    /** The roles each action's permission names, by action, as the sheet set them (comma separated);
     *  blank when it keeps the standing rule. */
    @Transactional(readOnly = true)
    public Map<String, String> permissions(String sheetId) {
        Map<String, String> permissions = new LinkedHashMap<>();
        for (String action : PERMISSION_ACTIONS) {
            permissions.put(action, settings.findBySheetIdAndKey(sheetId, permissionKey(action))
                    .map(SheetSetting::getValue).orElse(""));
        }
        return permissions;
    }

    /** Refuse an action the user's role is not allowed by the sheet's permission matrix (a blank
     *  permission keeps the standing rule), with the action's usual error. */
    @Transactional(readOnly = true)
    public void refuseUnlessPermitted(String sheetId, String user, String action, String refusal) {
        String value = settings.findBySheetIdAndKey(sheetId, permissionKey(action)).map(SheetSetting::getValue)
                .orElse("");
        Set<Role> allowed = value.isBlank() ? STANDING_PERMISSIONS.get(action) : rolesOf(value);
        if (!allowed.contains(roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, refusal);
        }
    }

    /** The roles a comma separated list names; refused when one of them is not a role. */
    private static Set<Role> rolesOf(String value) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String label : value.split(",")) {
            roles.add(Role.of(label.trim()).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Name the roles as a comma separated list of clerk and steward.")));
        }
        return roles;
    }

    /** Set the roles that may take an action, as a comma separated list; blank clears it, keeping the
     *  standing rule. Not dated. */
    @Transactional
    public String setPermission(String sheetId, String user, String action, String roles) {
        if (!PERMISSION_ACTIONS.contains(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such action.");
        }
        String value = roles == null || roles.isBlank() ? ""
                : String.join(",", Arrays.stream(roles.split(",")).map(String::trim).toList());
        if (!value.isEmpty()) rolesOf(value);
        sheets.load(sheetId);
        String key = permissionKey(action);
        SheetSetting setting = settings.findBySheetIdAndKey(sheetId, key).orElse(null);
        try {
            if (value.isEmpty()) {
                if (setting != null) {
                    settings.delete(setting);
                    settings.flush();
                }
            } else if (setting == null) {
                settings.saveAndFlush(new SheetSetting(sheetId, key, value));
            } else {
                setting.setValue(value);
                settings.saveAndFlush(setting);
            }
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The setting was changed by someone else.");
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + key + " value=" + value);
        return value;
    }

    /** The roles the sheet has assigned, by user name; any other name is a clerk. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The role the user name acts in on the sheet: a steward while a delegation to it covers the
     *  sheet's today, otherwise the one it was assigned (a clerk unless it was assigned another). */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return delegationOn(sheetId, user).isPresent() ? Role.STEWARD : assignedRoleOf(sheetId, user);
    }

    /** The role the sheet assigned a user name, leaving out any delegation: a clerk unless assigned another. */
    @Transactional(readOnly = true)
    public Role assignedRoleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUserName(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** The steward the user acts for while a delegation to it covers the sheet's today; the latest
     *  made when more than one does. */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> actingFor(String sheetId, String user) {
        return delegationOn(sheetId, user).map(Delegation::getDelegatedBy);
    }

    private Optional<Delegation> delegationOn(String sheetId, String user) {
        List<Delegation> made = delegations.findBySheetIdAndUserNameOrderByIdDesc(sheetId, user);
        if (made.isEmpty()) return Optional.empty();
        LocalDate today = sheets.today(sheetId);
        return made.stream().filter((d) -> d.covers(today)).findFirst();
    }

    /** The sheet's delegations in the order they were made. */
    @Transactional(readOnly = true)
    public List<Delegation> delegations(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Delegate the steward role to a user name from a date to a date, inclusive (blank from: the
     *  sheet's today); refused to anyone the sheet did not assign the steward role, and when the
     *  to-date is before the from-date. */
    @Transactional
    public Delegation delegate(String sheetId, String user, String userName, LocalDate from, LocalDate to) {
        sheets.load(sheetId);
        if (assignedRoleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can delegate the steward role.");
        }
        LocalDate start = from != null ? from : sheets.today(sheetId);
        LocalDate end = to != null ? to : start;
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date.");
        }
        Delegation saved = delegations.saveAndFlush(new Delegation(sheetId, userName, start, end, user));
        audit.recordOnCommit("DELEGATED sheet=" + sheetId + " by=" + user + " user=" + userName + " from=" + start
                + " to=" + end);
        return saved;
    }

    /** Assign a user name a role on the sheet, replacing the one it had. */
    @Transactional
    public SheetRole setRole(String sheetId, String user, String userName, Role role) {
        sheets.load(sheetId);
        SheetRole assigned = roles.findBySheetIdAndUserName(sheetId, userName).orElse(null);
        if (assigned == null) {
            assigned = new SheetRole(sheetId, userName, role);
        } else {
            assigned.setRole(role);
        }
        SheetRole saved;
        try {
            saved = roles.saveAndFlush(assigned);
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The role was changed by someone else.");
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + userName
                + " role=" + role.label());
        return saved;
    }
}
