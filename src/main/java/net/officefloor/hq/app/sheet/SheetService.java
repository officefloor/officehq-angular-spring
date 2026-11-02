package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet that was never loaded before starts empty. */
@Service
public class SheetService {

    /** The grades a person can have, each of which may have terms of its own. */
    private static final List<String> GRADES = List.of("A", "B", "C");

    private final SheetRepository sheets;
    private final SettingValueRepository settingValues;
    /** Lazy: the rollup reads the sheet's today through this service. */
    private final Rollup rollup;
    private final Audit audit;

    public SheetService(SheetRepository sheets, SettingValueRepository settingValues, @Lazy Rollup rollup, Audit audit) {
        this.sheets = sheets;
        this.settingValues = settingValues;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet with this id, created empty the first time it is loaded, its today the real date. */
    @Transactional
    public synchronized Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> sheets.save(new Sheet(id, user, LocalDate.now())));
    }

    /** The sheet's today: the date every date-dependent decision and default date on it uses. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(LocalDate::now);
    }

    /** The next claim number on the sheet: 1 for its first opened claim, then 2, and so on. */
    @Transactional
    public int nextClaimNumber(String id) {
        if (sheets.advanceClaimNumber(id) != 1) {
            throw new IllegalStateException("No sheet " + id);
        }
        return sheets.lastClaimNumber(id);
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

    /** Change the sheet's today; the figures it moves are recorded, with no record of its own. */
    @Transactional
    public synchronized Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setToday(today);
            return sheets.saveAndFlush(sheet);
        });
    }

    /** The floor a sheet starts with, in force before every from-date of its own. */
    public static final BigDecimal STARTING_FLOOR = new BigDecimal("1.00");
    /** The late percent a sheet starts with, in force before every from-date of its own. */
    public static final BigDecimal STARTING_LATE_PCT = new BigDecimal("0.00");
    /** The terms a sheet starts with, in force before every from-date of its own. */
    public static final int STARTING_TERMS = 14;

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
     *  it is unchanged, with the from-date at the end when one was given. */
    private Sheet record(String id, String user, String key, BigDecimal value, String shown, LocalDate from) {
        Sheet sheet = load(id, user);
        LocalDate on = from != null ? from : sheet.getToday();
        SettingValue v = settingValues.findById(new SettingValue.Key(id, key, on))
                .orElseGet(() -> new SettingValue(id, key, on, value));
        v.setValue(value);
        settingValues.saveAndFlush(v);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=" + key + " value=" + shown
                + (from != null ? " from=" + from : ""));
        return sheet;
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
        for (String grade : GRADES) {
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

    /** The percent of its figure an overdue claim on the sheet dated on the day gains for every full
     *  30 days it is overdue: the late percent in force on it, or else the sheet's starting one. */
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

    /** The sheet's policy date as set; null when it is blank (the sheet's today then stands in). */
    @Transactional(readOnly = true)
    public LocalDate policyDate(String id) {
        return sheets.findById(id).map(Sheet::getPolicyDate).orElse(null);
    }

    /** The policy date in force: the one set, or else the sheet's today. */
    @Transactional(readOnly = true)
    public LocalDate policyDateInForce(String id) {
        return sheets.findById(id).map(s -> s.getPolicyDate() != null ? s.getPolicyDate() : s.getToday())
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

    /** The most a person's due-total on the sheet comes to: the cap in force on the sheet's today;
     *  null when there is no cap. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String id) {
        return inForce(id, "cap", today(id)).map(SettingValue::getValue).orElse(null);
    }

    /** Record the sheet's cap (null: no cap) from a date (null: the sheet's today), then the figures
     *  it moves. */
    @Transactional
    public synchronized Sheet setCap(String id, String user, BigDecimal cap, LocalDate from) {
        load(id, user);
        return rollup.tracking(id, user,
                () -> record(id, user, "cap", cap, cap == null ? "" : cap.setScale(2).toPlainString(), from));
    }
}
