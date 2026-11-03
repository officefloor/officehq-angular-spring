package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Proof;
import net.officefloor.hq.app.grade.RetiredGradeRepository;
import net.officefloor.hq.app.grade.SheetGradeRepository;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Loads sheets by id; a sheet that was never loaded before starts empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final SettingValueRepository settingValues;
    private final FiscalClaimNumberRepository fiscalNumbers;
    private final SheetPermissionRepository permissions;
    private final ClaimRepository claims;
    /** Lazy: the rollup reads the sheet's today through this service. */
    private final Rollup rollup;
    /** Lazy: closing a period reads the sheet through this service. */
    private final PeriodService periods;
    /** Lazy: the roles read the sheet's today through this service. */
    private final RoleService roles;
    /** Lazy: the history reads the sheet's settings through their repositories. */
    private final History history;
    private final Audit audit;
    private final RetiredGradeRepository retiredGrades;
    private final SheetGradeRepository sheetGrades;
    private final PolicyVersionRepository policyVersions;

    public SheetService(SheetRepository sheets, SettingValueRepository settingValues,
            FiscalClaimNumberRepository fiscalNumbers, SheetPermissionRepository permissions, ClaimRepository claims, @Lazy Rollup rollup, @Lazy PeriodService periods,
            @Lazy RoleService roles, @Lazy History history, Audit audit, RetiredGradeRepository retiredGrades,
            SheetGradeRepository sheetGrades, PolicyVersionRepository policyVersions) {
        this.policyVersions = policyVersions;
        this.retiredGrades = retiredGrades;
        this.sheetGrades = sheetGrades;
        this.history = history;
        this.sheets = sheets;
        this.settingValues = settingValues;
        this.fiscalNumbers = fiscalNumbers;
        this.permissions = permissions;
        this.claims = claims;
        this.rollup = rollup;
        this.periods = periods;
        this.roles = roles;
        this.audit = audit;
    }

    /** The sheet with this id, created empty the first time it is loaded, its today the real date. */
    @Transactional
    public synchronized Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> sheets.save(new Sheet(id, user, LocalDate.now())));
    }

    /** Every grade of the sheet, each of which may have terms of its own: A, B and C, then those
     *  added to it in the order they were added. */
    @Transactional(readOnly = true)
    public List<String> grades(String id) {
        return sheetGrades.gradesOf(id);
    }

    /** Whether the grade is one of the sheet's. */
    @Transactional(readOnly = true)
    public boolean isGrade(String id, String grade) {
        return grade != null && sheetGrades.isGradeOf(id, grade);
    }

    /** Whether the sheet is archived (it then refuses every change). */
    @Transactional(readOnly = true)
    public boolean archived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }

    /** Archive the sheet. Refused unless the user acts as a steward on it, or when it is archived
     *  already. */
    @Transactional
    public synchronized Sheet archive(String id, String user) {
        Sheet sheet = load(id, user);
        if (!SheetRole.STEWARD.equals(roles.roleOf(id, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may archive the sheet");
        }
        if (sheet.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet is archived already");
        }
        sheet.setArchived(true);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + user);
        return saved;
    }

    /** The sheet's today: the date every date-dependent decision and default date on it uses; while a
     *  read from a screen previewing a date is answered, that date. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        LocalDate preview = PreviewDay.current();
        if (preview != null) {
            return preview;
        }
        return sheets.findById(id).map(Sheet::getToday).orElseGet(LocalDate::now);
    }

    /** The next claim number on the sheet for a claim due by the day: <FY>-<n>, counting from 1 within
     *  the fiscal year (by the sheet's fiscal start month) the day falls in; the sheets of a tree share
     *  one sequence per fiscal year, so it is the next after the last number given within that fiscal
     *  year on any sheet of the sheet's tree. */
    @Transactional
    public String nextClaimNumber(String id, LocalDate dueBy) {
        // the tree's root row is locked until the transaction ends, so two claims opened on its
        // sheets at once get one number each
        String root = sheets.root(id);
        sheets.lockClaimNumber(root);
        if (sheets.lockClaimNumber(id) != 1) {
            throw new IllegalStateException("No sheet " + id);
        }
        String fiscalYear = PeriodService.fiscalYear(dueBy, fiscalStartMonth(id));
        List<String> tree = new ArrayList<>(sheets.descendants(root));
        tree.add(root);
        int next = fiscalNumbers.lastNumber(tree, fiscalYear) + 1;
        FiscalClaimNumber last = fiscalNumbers.findById(new FiscalClaimNumber.Key(id, fiscalYear))
                .orElseGet(() -> new FiscalClaimNumber(id, fiscalYear, 0));
        last.setLastNumber(next);
        fiscalNumbers.saveAndFlush(last);
        return fiscalYear + "-" + next;
    }

    /** The next settle batch number on the sheet: 1 for its first bulk settle that settled a claim,
     *  then 2, and so on. */
    @Transactional
    public int nextSettleBatch(String id) {
        if (sheets.advanceSettleBatch(id) != 1) {
            throw new IllegalStateException("No sheet " + id);
        }
        return sheets.lastSettleBatch(id);
    }

    /** The number of the last settle batch on the sheet; null when there has been none. */
    @Transactional(readOnly = true)
    public Integer lastSettleBatch(String id) {
        Integer last = sheets.lastSettleBatch(id);
        return last == null || last == 0 ? null : last;
    }

    /** The next X-number on the sheet, for a claim in no period opened under period numbering: 1,
     *  then 2, and so on. */
    @Transactional
    public int nextUnperiodNumber(String id) {
        if (sheets.advanceUnperiodNumber(id) != 1) {
            throw new IllegalStateException("No sheet " + id);
        }
        return sheets.lastUnperiodNumber(id);
    }

    /** Change the sheet's today, first recording each person's due-total on the day being left,
     *  closing every claim editor held open on it
     *  when the day is a new one; then, when the sheet has grace days, close
     *  the open periods they have run out on; then the figures it moves are recorded. */
    @Transactional
    public synchronized Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        if (!roles.may(id, user, "today")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not change the sheet's today");
        }
        if (!today.equals(sheet.getToday())) {
            claims.releaseEditLocks(id);
        }
        if (sheet.isScoreLog()) {
            rollup.recordScores(id, user);
        }
        rollup.recordSnapshot(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setToday(today);
            Sheet saved = sheets.saveAndFlush(sheet);
            if (saved.getGraceDays() != null) {
                periods.autoClose(id, user, saved.getGraceDays(), today);
            }
            return saved;
        });
    }

    /** The floor a sheet starts with, in force before every from-date of its own. */
    public static final BigDecimal STARTING_FLOOR = new BigDecimal("1.00");
    /** The late percent a sheet starts with, in force before every from-date of its own. */
    public static final BigDecimal STARTING_LATE_PCT = new BigDecimal("0.00");
    /** The terms a sheet starts with, in force before every from-date of its own. */
    public static final int STARTING_TERMS = 14;
    /** The grade a new person takes when none is given and the sheet's default grade is blank. */
    public static final String DEFAULT_GRADE = "A";

    /** The value a dated setting of the sheet has recorded in force on a day: the one with the latest
     *  from-date on or before it; empty when none is (the value itself is null when blank). */
    private Optional<SettingValue> inForce(String id, String key, LocalDate day) {
        return settingValues.findFirstBySheetIdAndKeyAndFromLessThanEqualOrderByFromDesc(id, key, day);
    }

    /** The values recorded for a dated setting of the sheet, earliest from-date first. */
    @Transactional(readOnly = true)
    public List<SettingValue> recorded(String id, String key) {
        return settingValues.findBySheetIdAndKeyOrderByFrom(id, key);
    }

    /** Record a dated setting's value from a date (the sheet's today when none is given), keeping the
     *  values already recorded (one from the same date is replaced); recorded in the audit even when
     *  it is unchanged, with the from-date at the end when one was given. A policy setting (see
     *  {@link PolicyVersion#KEYS}) makes a new policy version from that date. */
    private Sheet record(String id, String user, String key, BigDecimal value, String shown, LocalDate from) {
        Sheet sheet = load(id, user);
        LocalDate on = from != null ? from : sheet.getToday();
        SettingValue v = settingValues.findById(new SettingValue.Key(id, key, on))
                .orElseGet(() -> new SettingValue(id, key, on, value));
        v.setValue(value);
        settingValues.saveAndFlush(v);
        if (PolicyVersion.KEYS.contains(key)) {
            policyVersions.saveAndFlush(
                    new PolicyVersion(id, policyVersions.countBySheetId(id) + 1, key, on, sheet.getToday(), shown));
        }
        history.record(id);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=" + key + " value=" + shown
                + (from != null ? " from=" + from : ""));
        return sheet;
    }

    /** The number of the sheet's current policy version: how many have been made; 0 when none has. */
    @Transactional(readOnly = true)
    public int policyVersion(String id) {
        return policyVersions.countBySheetId(id);
    }

    /** A policy version as the viewer lists it: the version, and how many of the sheet's claims have it
     *  as their policy version (the one in force on the claim's date). */
    public record PolicyLine(PolicyVersion version, long claims) {
    }

    /** The sheet's policy versions, the first made first, each with its count of claims. */
    @Transactional(readOnly = true)
    public List<PolicyLine> policyLines(String id) {
        List<PolicyVersion> versions = policyVersions.findBySheetIdOrderByNumber(id);
        Map<Integer, Long> counts = new TreeMap<>();
        claims.findBySheetIdOrderByListedDesc(id)
                .forEach(c -> counts.merge(PolicyVersion.on(versions, c.getDate()), 1L, Long::sum));
        return versions.stream().map(v -> new PolicyLine(v, counts.getOrDefault(v.getNumber(), 0L))).toList();
    }

    /** The sheet's policy versions, the first made first. */
    @Transactional(readOnly = true)
    public List<PolicyVersion> policyVersions(String id) {
        return policyVersions.findBySheetIdOrderByNumber(id);
    }

    /** The most due a clerk may settle on a claim on the sheet: the limit in force on the sheet's
     *  today; null when there is no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String id) {
        return inForce(id, "clerk-limit", today(id)).map(SettingValue::getValue).orElse(null);
    }

    /** Record the sheet's clerk limit (null: no limit) from a date (null: the sheet's today). */
    @Transactional
    public synchronized Sheet setClerkLimit(String id, String user, BigDecimal limit, LocalDate from) {
        return record(id, user, "clerk-limit", limit, limit == null ? "" : limit.setScale(2).toPlainString(), from);
    }

    /** The clerk limits each grade on the sheet has of its own in force on its today, by grade; a
     *  grade not in it takes the sheet's (blank, or never set). */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> gradeClerkLimits(String id) {
        LocalDate today = today(id);
        Map<String, BigDecimal> limits = new TreeMap<>();
        for (String grade : grades(id)) {
            inForce(id, "clerk-limit-" + grade, today).map(SettingValue::getValue)
                    .ifPresent(v -> limits.put(grade, v));
        }
        return limits;
    }

    /** The most due a clerk may settle on a claim dated on the day of a person of the grade: the
     *  grade's own clerk limit in force on the day when one is set, or else the sheet's; null when
     *  neither is. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimitFor(String id, String grade, LocalDate day) {
        return inForce(id, "clerk-limit-" + grade, day).map(SettingValue::getValue)
                .orElseGet(() -> inForce(id, "clerk-limit", day).map(SettingValue::getValue).orElse(null));
    }

    /** Record a grade's own clerk limit (null: blank, the sheet's) from a date (null: the sheet's
     *  today). It changes no figure. */
    @Transactional
    public synchronized Sheet setGradeClerkLimit(String id, String user, String grade, BigDecimal limit,
            LocalDate from) {
        return record(id, user, "clerk-limit-" + grade, limit,
                limit == null ? "" : limit.setScale(2).toPlainString(), from);
    }

    /** The least live due a claim on the sheet dated on the day has: the floor in force on it, or
     *  else the sheet's starting floor. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String id, LocalDate day) {
        return inForce(id, "floor", day).map(SettingValue::getValue).orElse(STARTING_FLOOR);
    }

    /** The floor in force on the sheet's today. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String id) {
        return floor(id, today(id));
    }

    /** Record the sheet's floor from a date (null: the sheet's today), then the figures it moves. */
    @Transactional
    public synchronized Sheet setFloor(String id, String user, BigDecimal floor, LocalDate from) {
        load(id, user);
        return rollup.tracking(id, user,
                () -> record(id, user, "floor", floor, floor.setScale(2).toPlainString(), from));
    }

    /** The days after its date a claim on the sheet dated on the day is due by: the sheet's terms in
     *  force on it, or else its starting terms. */
    @Transactional(readOnly = true)
    public int terms(String id, LocalDate day) {
        return inForce(id, "terms", day).map(v -> v.getValue().intValue()).orElse(STARTING_TERMS);
    }

    /** The sheet's terms in force on its today. */
    @Transactional(readOnly = true)
    public int terms(String id) {
        return terms(id, today(id));
    }

    /** Record the sheet's terms from a date (null: the sheet's today). They change no claim's due-by,
     *  which is fixed when the claim is made. */
    @Transactional
    public synchronized Sheet setTerms(String id, String user, int terms, LocalDate from) {
        load(id, user);
        return rollup.tracking(id, user,
                () -> record(id, user, "terms", BigDecimal.valueOf(terms), String.valueOf(terms), from));
    }

    /** The terms each grade on the sheet has of its own in force on its today, by grade; a grade not
     *  in it takes the sheet's terms. */
    @Transactional(readOnly = true)
    public Map<String, Integer> gradeTerms(String id) {
        LocalDate today = today(id);
        Map<String, Integer> terms = new TreeMap<>();
        for (String grade : grades(id)) {
            inForce(id, "terms-" + grade, today).ifPresent(v -> terms.put(grade, v.getValue().intValue()));
        }
        return terms;
    }

    /** The terms in force on a day for a grade on the sheet: its own in force on the day, or else the
     *  sheet's. */
    @Transactional(readOnly = true)
    public int termsFor(String id, String grade, LocalDate day) {
        return inForce(id, "terms-" + grade, day).map(v -> v.getValue().intValue())
                .orElseGet(() -> terms(id, day));
    }

    /** Record a grade's own terms from a date (null: the sheet's today). They change no claim's
     *  due-by, which is fixed when the claim is made. */
    @Transactional
    public synchronized Sheet setGradeTerms(String id, String user, String grade, int terms, LocalDate from) {
        return record(id, user, "terms-" + grade, BigDecimal.valueOf(terms), String.valueOf(terms), from);
    }

    /** The grace days each grade on the sheet has in force on its today, by grade; a grade not in it
     *  has none (blank, or never set). */
    @Transactional(readOnly = true)
    public Map<String, Integer> gradeGraces(String id) {
        LocalDate today = today(id);
        Map<String, Integer> graces = new TreeMap<>();
        for (String grade : grades(id)) {
            inForce(id, "grace-" + grade, today).map(SettingValue::getValue)
                    .ifPresent(v -> graces.put(grade, v.intValue()));
        }
        return graces;
    }

    /** The grace days a claim on the sheet dated on the day has for a grade: the grade's grace in
     *  force on it; 0 when none is (blank, or never set). */
    @Transactional(readOnly = true)
    public int grace(String id, String grade, LocalDate day) {
        return inForce(id, "grace-" + grade, day).map(SettingValue::getValue).map(BigDecimal::intValue).orElse(0);
    }

    /** Record a grade's grace days (null: blank, none) from a date (null: the sheet's today), then the
     *  figures they move. They change no claim's due-by. */
    @Transactional
    public synchronized Sheet setGrace(String id, String user, String grade, Integer days, LocalDate from) {
        load(id, user);
        return rollup.tracking(id, user, () -> record(id, user, "grace-" + grade,
                days == null ? null : BigDecimal.valueOf(days), days == null ? "" : days.toString(), from));
    }

    /** The percent of its figure an overdue claim on the sheet gains for a full 30 days overdue ending
     *  on the day: the late percent in force on it, or else the sheet's starting one. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String id, LocalDate day) {
        return inForce(id, "late-pct", day).map(SettingValue::getValue).orElse(STARTING_LATE_PCT);
    }

    /** The late percent in force on the sheet's today. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String id) {
        return latePct(id, today(id));
    }

    /** Record the sheet's late percent from a date (null: the sheet's today), then the figures it
     *  moves. */
    @Transactional
    public synchronized Sheet setLatePct(String id, String user, BigDecimal latePct, LocalDate from) {
        load(id, user);
        return rollup.tracking(id, user,
                () -> record(id, user, "late-pct", latePct, latePct.setScale(2).toPlainString(), from));
    }

    /** The rounding mode in force on a day on the sheet; empty when none is (blank, or never set). */
    @Transactional(readOnly = true)
    public Optional<Rounding> rounding(String id, LocalDate day) {
        return inForce(id, Rounding.KEY, day).map(SettingValue::getValue).flatMap(Rounding::ofCode);
    }

    /** The rounding mode in force on the sheet's today; empty when none is. */
    @Transactional(readOnly = true)
    public Optional<Rounding> rounding(String id) {
        return rounding(id, today(id));
    }

    /** Record the sheet's rounding mode (null: blank, none) from a date (null: the sheet's today), then
     *  the figures it moves. */
    @Transactional
    public synchronized Sheet setRounding(String id, String user, Rounding rounding, LocalDate from) {
        load(id, user);
        return rollup.tracking(id, user, () -> record(id, user, Rounding.KEY,
                rounding == null ? null : rounding.code(), rounding == null ? "" : rounding.text(), from));
    }

    /** The sheet's policy date as set; null when it is blank (the sheet's today then stands in). */
    @Transactional(readOnly = true)
    public LocalDate policyDate(String id) {
        return sheets.findById(id).map(Sheet::getPolicyDate).orElse(null);
    }

    /** The policy date in force: the one set, or else the sheet's today. */
    @Transactional(readOnly = true)
    public LocalDate policyDateInForce(String id) {
        return sheets.findById(id).map(s -> s.getPolicyDate() != null ? s.getPolicyDate() : today(id))
                .orElseGet(LocalDate::now);
    }

    /** Change the sheet's policy date (null: blank, the sheet's today); recorded even when it is
     *  unchanged, then the figures it moves. */
    @Transactional
    public synchronized Sheet setPolicyDate(String id, String user, LocalDate policyDate) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setPolicyDate(policyDate);
            Sheet saved = sheets.saveAndFlush(sheet);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=policy-date value="
                    + (policyDate == null ? "" : policyDate.toString()));
            return saved;
        });
    }

    /** The sheet's period numbering from date; null when it is blank (off). */
    @Transactional(readOnly = true)
    public LocalDate periodNumberingFrom(String id) {
        return sheets.findById(id).map(Sheet::getPeriodNumberingFrom).orElse(null);
    }

    /** Whether a claim opened on the sheet now is numbered within its period: the setting is a date
     *  and the sheet's today is on or after it. */
    @Transactional(readOnly = true)
    public boolean periodNumbering(String id) {
        return sheets.findById(id)
                .map(s -> s.getPeriodNumberingFrom() != null && !s.getToday().isBefore(s.getPeriodNumberingFrom()))
                .orElse(false);
    }

    /** Change the sheet's period numbering from date (null: blank, off); recorded even when it is
     *  unchanged. It changes no figure, and no number already given. */
    @Transactional
    public synchronized Sheet setPeriodNumberingFrom(String id, String user, LocalDate from) {
        Sheet sheet = load(id, user);
        sheet.setPeriodNumberingFrom(from);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=period-numbering-from value="
                + (from == null ? "" : from.toString()));
        return saved;
    }

    /** The sheet's grace days; null when blank (off). */
    @Transactional(readOnly = true)
    public Integer graceDays(String id) {
        return sheets.findById(id).map(Sheet::getGraceDays).orElse(null);
    }

    /** Change the sheet's grace days (null: blank, off); recorded even when unchanged. It closes no
     *  period until the sheet's today is next changed. */
    @Transactional
    public synchronized Sheet setGraceDays(String id, String user, Integer graceDays) {
        Sheet sheet = load(id, user);
        sheet.setGraceDays(graceDays);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=grace-days value="
                + (graceDays == null ? "" : graceDays.toString()));
        return saved;
    }

    /** The sheet's entry date; null when blank (off). */
    @Transactional(readOnly = true)
    public LocalDate entryDate(String id) {
        return sheets.findById(id).map(Sheet::getEntryDate).orElse(null);
    }

    /** The default date of a new claim on the sheet: its entry date while one is set, or else its
     *  today. */
    @Transactional(readOnly = true)
    public LocalDate entryDay(String id) {
        return sheets.findById(id).map(s -> s.getEntryDate() != null ? s.getEntryDate() : s.getToday())
                .orElseGet(LocalDate::now);
    }

    /** Change the sheet's entry date (null: blank, off); recorded even when unchanged. It changes no
     *  claim already added. */
    @Transactional
    public synchronized Sheet setEntryDate(String id, String user, LocalDate entryDate) {
        Sheet sheet = load(id, user);
        sheet.setEntryDate(entryDate);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=entry-date value="
                + (entryDate == null ? "" : entryDate.toString()));
        return saved;
    }

    /** The sheet's pot; null when blank (none). */
    @Transactional(readOnly = true)
    public BigDecimal pot(String id) {
        return sheets.findById(id).map(Sheet::getPot).orElse(null);
    }

    /** Change the sheet's pot (null: blank, none); recorded even when unchanged, then the figures
     *  it moves (the pools' cuts count against the cap). */
    @Transactional
    public synchronized Sheet setPot(String id, String user, BigDecimal pot) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setPot(pot);
            Sheet saved = sheets.saveAndFlush(sheet);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=pot value="
                    + (pot == null ? "" : pot.setScale(2).toPlainString()));
            return saved;
        });
    }

    /** The sheet's escalation gap in days; null when blank (off). */
    @Transactional(readOnly = true)
    public Integer escalateAfter(String id) {
        return sheets.findById(id).map(Sheet::getEscalateAfter).orElse(null);
    }

    /** Change the sheet's escalation gap (null: blank, off); recorded even when unchanged. */
    @Transactional
    public synchronized Sheet setEscalateAfter(String id, String user, Integer days) {
        Sheet sheet = load(id, user);
        sheet.setEscalateAfter(days);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=escalate-after value="
                + (days == null ? "" : days.toString()));
        return saved;
    }

    /** The sheet's maximum of open claims per person; null when blank (none). */
    @Transactional(readOnly = true)
    public Integer maxOpen(String id) {
        return sheets.findById(id).map(Sheet::getMaxOpen).orElse(null);
    }

    /** Change the sheet's maximum of open claims per person (null: blank, none); recorded even when
     *  unchanged. */
    @Transactional
    public synchronized Sheet setMaxOpen(String id, String user, Integer max) {
        Sheet sheet = load(id, user);
        sheet.setMaxOpen(max);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=max-open value="
                + (max == null ? "" : max.toString()));
        return saved;
    }

    /** The sheet's review SLA in days; null when blank (none). */
    @Transactional(readOnly = true)
    public Integer reviewSla(String id) {
        return sheets.findById(id).map(Sheet::getReviewSla).orElse(null);
    }

    /** Change the sheet's review SLA (null: blank, none); recorded even when unchanged. */
    @Transactional
    public synchronized Sheet setReviewSla(String id, String user, Integer days) {
        Sheet sheet = load(id, user);
        sheet.setReviewSla(days);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=review-sla value="
                + (days == null ? "" : days.toString()));
        return saved;
    }

    /** The sheet's pending SLA in days; null when blank (none). */
    @Transactional(readOnly = true)
    public Integer pendingSla(String id) {
        return sheets.findById(id).map(Sheet::getPendingSla).orElse(null);
    }

    /** Change the sheet's pending SLA (null: blank, none); recorded even when unchanged. */
    @Transactional
    public synchronized Sheet setPendingSla(String id, String user, Integer days) {
        Sheet sheet = load(id, user);
        sheet.setPendingSla(days);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=pending-sla value="
                + (days == null ? "" : days.toString()));
        return saved;
    }

    /** The sheet's mid score threshold; null when blank. */
    @Transactional(readOnly = true)
    public Integer scoreMid(String id) {
        return sheets.findById(id).map(Sheet::getScoreMid).orElse(null);
    }

    /** Change the sheet's mid score threshold (null: blank); recorded even when unchanged. */
    @Transactional
    public synchronized Sheet setScoreMid(String id, String user, Integer value) {
        Sheet sheet = load(id, user);
        sheet.setScoreMid(value);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=score-mid value="
                + (value == null ? "" : value.toString()));
        return saved;
    }

    /** The sheet's high score threshold; null when blank. */
    @Transactional(readOnly = true)
    public Integer scoreHigh(String id) {
        return sheets.findById(id).map(Sheet::getScoreHigh).orElse(null);
    }

    /** Change the sheet's high score threshold (null: blank); recorded even when unchanged. */
    @Transactional
    public synchronized Sheet setScoreHigh(String id, String user, Integer value) {
        Sheet sheet = load(id, user);
        sheet.setScoreHigh(value);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=score-high value="
                + (value == null ? "" : value.toString()));
        return saved;
    }

    /** Whether the sheet's score log switch is on. */
    @Transactional(readOnly = true)
    public boolean scoreLog(String id) {
        return sheets.findById(id).map(Sheet::isScoreLog).orElse(false);
    }

    /** Turn the sheet's score log switch on or off (blank); recorded even when unchanged. */
    @Transactional
    public synchronized Sheet setScoreLog(String id, String user, boolean on) {
        Sheet sheet = load(id, user);
        sheet.setScoreLog(on);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=score-log value="
                + (on ? "on" : ""));
        return saved;
    }

    /** The sheet's alert threshold; null when blank (off). */
    @Transactional(readOnly = true)
    public BigDecimal alertThreshold(String id) {
        return sheets.findById(id).map(Sheet::getAlertThreshold).orElse(null);
    }

    /** Change the sheet's alert threshold (null: blank, off); recorded even when unchanged. */
    @Transactional
    public synchronized Sheet setAlertThreshold(String id, String user, BigDecimal threshold) {
        Sheet sheet = load(id, user);
        sheet.setAlertThreshold(threshold);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=alert-threshold value="
                + (threshold == null ? "" : threshold.setScale(2).toPlainString()));
        return saved;
    }

    /** The sheet's fiscal year start month, 1 to 12; 1 unless set. */
    @Transactional(readOnly = true)
    public int fiscalStartMonth(String id) {
        return sheets.findById(id).map(Sheet::getFiscalStartMonth).orElse(1);
    }

    /** Change the sheet's fiscal year start month (1 to 12); recorded even when unchanged. */
    @Transactional
    public synchronized Sheet setFiscalStartMonth(String id, String user, int month) {
        if (month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The fiscal year start month is 1 to 12");
        }
        Sheet sheet = load(id, user);
        sheet.setFiscalStartMonth(month);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=fiscal-start-month value=" + month);
        return saved;
    }

    /** The sheet's parent's id; null when blank (none). */
    @Transactional(readOnly = true)
    public String parent(String id) {
        return sheets.findById(id).map(Sheet::getParentId).orElse(null);
    }

    /** Change the sheet's parent (null: blank, none), then the figures it moves are recorded.
     *  Refused when the parent is the sheet itself, has the sheet as an ancestor, or is a sheet that
     *  was never loaded. */
    @Transactional
    public synchronized Sheet setParent(String id, String user, String parent) {
        Sheet sheet = load(id, user);
        if (parent != null) {
            if (parent.equals(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A sheet cannot be its own parent");
            }
            if (!sheets.existsById(parent)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No sheet " + parent);
            }
            if (hasAncestor(parent, id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The parent has this sheet as an ancestor");
            }
        }
        return rollup.tracking(id, user, () -> {
            sheet.setParentId(parent);
            Sheet saved = sheets.saveAndFlush(sheet);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=parent value="
                    + (parent == null ? "" : parent));
            return saved;
        });
    }

    /** The ids of the sheet's ancestors, nearest first, up to three levels up (see
     *  {@link SheetRepository#ancestors}). */
    @Transactional(readOnly = true)
    public List<String> ancestors(String id) {
        return sheets.ancestors(id);
    }

    /** The ids of the sheets the sheet's rates are taken from, nearest first: its ancestors, then
     *  the sheet it was cloned from and its own (see {@link SheetRepository#rateSources}). */
    @Transactional(readOnly = true)
    public List<String> rateSources(String id) {
        return sheets.rateSources(id);
    }

    /** The ids of the other sheets of the sheet's tree, among the sheets that exist: its ancestors,
     *  however many levels up, and its descendants, however many levels down. */
    @Transactional(readOnly = true)
    public Set<String> tree(String id) {
        Set<String> tree = new HashSet<>();
        String at = sheets.findById(id).map(Sheet::getParentId).orElse(null);
        while (at != null && !at.equals(id) && tree.add(at)) {
            at = sheets.findById(at).map(Sheet::getParentId).orElse(null);
        }
        Set<String> below = new HashSet<>();
        Deque<String> next = new ArrayDeque<>(List.of(id));
        while (!next.isEmpty()) {
            for (Sheet child : sheets.findByParentId(next.pop())) {
                if (!child.getId().equals(id) && below.add(child.getId())) {
                    next.add(child.getId());
                }
            }
        }
        tree.addAll(below);
        tree.retainAll(sheets.findAllById(tree).stream().map(Sheet::getId).toList());
        return tree;
    }

    /** Whether a sheet has another among its ancestors, however many levels up. */
    private boolean hasAncestor(String id, String ancestor) {
        Set<String> seen = new HashSet<>();
        String at = sheets.findById(id).map(Sheet::getParentId).orElse(null);
        while (at != null && seen.add(at)) {
            if (at.equals(ancestor)) {
                return true;
            }
            at = sheets.findById(at).map(Sheet::getParentId).orElse(null);
        }
        return false;
    }

    /** The most a person's due-total on the sheet comes to: the cap in force on the sheet's today;
     *  null when there is no cap. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String id) {
        return cap(id, today(id));
    }

    /** The cap in force on a day; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String id, LocalDate day) {
        return inForce(id, "cap", day).map(SettingValue::getValue).orElse(null);
    }

    /** Record the sheet's cap (null: no cap) from a date (null: the sheet's today), then the figures
     *  it moves. */
    @Transactional
    public synchronized Sheet setCap(String id, String user, BigDecimal cap, LocalDate from) {
        load(id, user);
        return rollup.tracking(id, user,
                () -> record(id, user, "cap", cap, cap == null ? "" : cap.setScale(2).toPlainString(), from));
    }

    /** The due above which a claim on the sheet dated on the day needs a proof before it is settled
     *  or approved: the proof threshold in force on it; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThreshold(String id, LocalDate day) {
        return inForce(id, "proof-threshold", day).map(SettingValue::getValue).orElse(null);
    }

    /** The proof thresholds each grade on the sheet has of its own in force on its today, by grade; a
     *  grade not in it takes the sheet's (blank, or never set). */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> gradeProofThresholds(String id) {
        LocalDate today = today(id);
        Map<String, BigDecimal> thresholds = new TreeMap<>();
        for (String grade : grades(id)) {
            inForce(id, "proof-threshold-" + grade, today).map(SettingValue::getValue)
                    .ifPresent(v -> thresholds.put(grade, v));
        }
        return thresholds;
    }

    /** The due above which a claim on the sheet dated on the day needs a proof for a grade: the
     *  grade's own proof threshold in force on it when one is set, or else the sheet's; null when
     *  neither is. */
    @Transactional(readOnly = true)
    public BigDecimal proofThresholdFor(String id, String grade, LocalDate day) {
        return inForce(id, "proof-threshold-" + grade, day).map(SettingValue::getValue)
                .orElseGet(() -> proofThreshold(id, day));
    }

    /** Record a grade's own proof threshold (null: blank, the sheet's) from a date (null: the sheet's
     *  today). It changes no figure. */
    @Transactional
    public synchronized Sheet setGradeProofThreshold(String id, String user, String grade, BigDecimal threshold,
            LocalDate from) {
        return record(id, user, "proof-threshold-" + grade, threshold,
                threshold == null ? "" : threshold.setScale(2).toPlainString(), from);
    }

    /** The proof threshold in force on the sheet's today. */
    @Transactional(readOnly = true)
    public BigDecimal proofThreshold(String id) {
        return proofThreshold(id, today(id));
    }

    /** Record the sheet's proof threshold (null: none) from a date (null: the sheet's today). It
     *  changes no figure. */
    @Transactional
    public synchronized Sheet setProofThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return record(id, user, "proof-threshold", threshold,
                threshold == null ? "" : threshold.setScale(2).toPlainString(), from);
    }

    /** The sheet's proof kinds: the kinds a claim above the proof threshold needs at least one proof
     *  of each before it is settled or approved; empty when blank (none). Not dated. */
    @Transactional(readOnly = true)
    public List<String> proofKinds(String id) {
        String kinds = sheets.findById(id).map(Sheet::getProofKinds).orElse(null);
        return kinds == null ? List.of() : List.of(kinds.split(","));
    }

    /** Change the sheet's proof kinds (empty: blank, none), each receipt or photo, kept in the order
     *  given without repeats. It changes no figure. */
    @Transactional
    public synchronized Sheet setProofKinds(String id, String user, List<String> kinds) {
        if (!Proof.KINDS.containsAll(kinds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A proof kind is receipt or photo");
        }
        Sheet sheet = load(id, user);
        String value = String.join(",", new LinkedHashSet<>(kinds));
        sheet.setProofKinds(value.isEmpty() ? null : value);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=proof-kinds value=" + value);
        return saved;
    }

    /** The grade a new person on the sheet takes when none is given (by the quick-add bar, or an
     *  import line with a blank grade): the sheet's default grade, or A when it is blank or retired.
     *  Not dated. */
    @Transactional(readOnly = true)
    public String defaultGrade(String id) {
        String grade = sheets.findById(id).map(Sheet::getDefaultGrade).orElse(null);
        return grade == null || retiredGrades.existsBySheetIdAndGrade(id, grade) ? DEFAULT_GRADE : grade;
    }

    /** The sheet's default grade as set; null when blank (A stands in). Not dated. */
    @Transactional(readOnly = true)
    public String defaultGradeSet(String id) {
        return sheets.findById(id).map(Sheet::getDefaultGrade).orElse(null);
    }

    /** Change the sheet's default grade (null: blank, A), one of the grades. It changes no one. */
    @Transactional
    public synchronized Sheet setDefaultGrade(String id, String user, String grade) {
        if (grade != null && !isGrade(id, grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A default grade is one of the sheet's grades");
        }
        Sheet sheet = load(id, user);
        sheet.setDefaultGrade(grade);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=default-grade value="
                + (grade == null ? "" : grade));
        return saved;
    }

    /** The due above which a claim on the sheet dated on the day cannot be opened with an empty
     *  note: the note threshold in force on it; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal noteThreshold(String id, LocalDate day) {
        return inForce(id, "note-threshold", day).map(SettingValue::getValue).orElse(null);
    }

    /** The note threshold in force on the sheet's today. */
    @Transactional(readOnly = true)
    public BigDecimal noteThreshold(String id) {
        return noteThreshold(id, today(id));
    }

    /** Record the sheet's note threshold (null: none) from a date (null: the sheet's today). It
     *  changes no figure. */
    @Transactional
    public synchronized Sheet setNoteThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return record(id, user, "note-threshold", threshold,
                threshold == null ? "" : threshold.setScale(2).toPlainString(), from);
    }

    /** The due above which a claim on the sheet dated on the day goes to review when it is opened:
     *  the review threshold in force on it; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal reviewThreshold(String id, LocalDate day) {
        return inForce(id, "review-threshold", day).map(SettingValue::getValue).orElse(null);
    }

    /** The review threshold in force on the sheet's today. */
    @Transactional(readOnly = true)
    public BigDecimal reviewThreshold(String id) {
        return reviewThreshold(id, today(id));
    }

    /** Record the sheet's review threshold (null: none) from a date (null: the sheet's today). It
     *  changes no figure. */
    @Transactional
    public synchronized Sheet setReviewThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return record(id, user, "review-threshold", threshold,
                threshold == null ? "" : threshold.setScale(2).toPlainString(), from);
    }

    /** The actions the permission matrix covers. */
    public static final List<String> ACTIONS = List.of("open", "settle", "void", "reassign", "close", "reopen", "today");

    /** The roles that may take each action under the standing rule (a blank entry in the matrix). */
    private static final Map<String, List<String>> STANDING_PERMISSIONS = Map.of(
            "open", List.of("clerk", "steward"), "settle", List.of("clerk", "steward"),
            "void", List.of("clerk", "steward"), "close", List.of("clerk", "steward"),
            "reassign", List.of("steward"), "reopen", List.of("steward"),
            "today", List.of("clerk", "steward"));

    /** The permission matrix as set on the sheet: for each action, the comma separated roles that may
     *  take it, blank ("") when it keeps the standing rule. */
    @Transactional(readOnly = true)
    public Map<String, String> permissions(String id) {
        Map<String, String> matrix = new TreeMap<>();
        for (String action : ACTIONS) {
            matrix.put(action, "");
        }
        for (SheetPermission p : permissions.findBySheetId(id)) {
            matrix.put(p.getAction(), p.getRoles());
        }
        return matrix;
    }

    /** The roles that may take an action on the sheet: those set in the matrix, or else the standing
     *  rule's. */
    @Transactional(readOnly = true)
    public List<String> rolesAllowed(String id, String action) {
        return permissions.findById(new SheetPermission.Key(id, action))
                .map(p -> List.of(p.getRoles().split(",")))
                .orElse(STANDING_PERMISSIONS.get(action));
    }

    /** Set the roles (a comma separated list; blank: the standing rule) that may take an action on the
     *  sheet; recorded even when it is unchanged. */
    @Transactional
    public synchronized Sheet setPermission(String id, String user, String action, String roles) {
        Sheet sheet = load(id, user);
        if (roles.isEmpty()) {
            permissions.deleteById(new SheetPermission.Key(id, action));
        } else {
            SheetPermission p = permissions.findById(new SheetPermission.Key(id, action))
                    .orElseGet(() -> new SheetPermission(id, action, roles));
            p.setRoles(roles);
            permissions.saveAndFlush(p);
        }
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=perm-" + action + " value=" + roles);
        return sheet;
    }
}
