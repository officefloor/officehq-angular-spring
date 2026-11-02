package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet that was never loaded before starts empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final GradeTermsRepository gradeTerms;
    /** Lazy: the rollup reads the sheet's today through this service. */
    private final Rollup rollup;
    private final Audit audit;

    public SheetService(SheetRepository sheets, GradeTermsRepository gradeTerms, @Lazy Rollup rollup, Audit audit) {
        this.sheets = sheets;
        this.gradeTerms = gradeTerms;
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

    /** The most due a clerk may settle on a claim on the sheet; null when there is no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String id) {
        return sheets.findById(id).map(Sheet::getClerkLimit).orElse(null);
    }

    /** Change the sheet's clerk limit (null: no limit); recorded even when it is unchanged. */
    @Transactional
    public synchronized Sheet setClerkLimit(String id, String user, BigDecimal limit) {
        Sheet sheet = load(id, user);
        sheet.setClerkLimit(limit);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=clerk-limit value="
                + (limit == null ? "" : limit.setScale(2).toPlainString()));
        return saved;
    }

    /** The least live due a claim on the sheet has; 1.00 for a sheet never loaded. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String id) {
        return sheets.findById(id).map(Sheet::getFloor).orElse(new BigDecimal("1.00"));
    }

    /** Change the sheet's floor; recorded even when it is unchanged, then the figures it moves. */
    @Transactional
    public synchronized Sheet setFloor(String id, String user, BigDecimal floor) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setFloor(floor);
            Sheet saved = sheets.saveAndFlush(sheet);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=floor value="
                    + floor.setScale(2).toPlainString());
            return saved;
        });
    }

    /** The days after its date a claim on the sheet is due by; 14 for a sheet never loaded. */
    @Transactional(readOnly = true)
    public int terms(String id) {
        return sheets.findById(id).map(Sheet::getTerms).orElse(14);
    }

    /** Change the sheet's terms; recorded even when they are unchanged. They change no claim's
     *  due-by, which is fixed when the claim is made. */
    @Transactional
    public synchronized Sheet setTerms(String id, String user, int terms) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setTerms(terms);
            Sheet saved = sheets.saveAndFlush(sheet);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=terms value=" + terms);
            return saved;
        });
    }

    /** The terms each grade on the sheet has of its own, by grade; a grade not in it takes the
     *  sheet's terms. */
    @Transactional(readOnly = true)
    public Map<String, Integer> gradeTerms(String id) {
        Map<String, Integer> terms = new TreeMap<>();
        gradeTerms.findBySheetIdOrderByGrade(id).forEach(t -> terms.put(t.getGrade(), t.getTerms()));
        return terms;
    }

    /** The terms in force for a grade on the sheet: its own, or else the sheet's. */
    @Transactional(readOnly = true)
    public int termsFor(String id, String grade) {
        return gradeTerms.findById(new GradeTerms.Key(id, grade)).map(GradeTerms::getTerms)
                .orElseGet(() -> terms(id));
    }

    /** Change a grade's own terms; recorded even when they are unchanged. They change no claim's
     *  due-by, which is fixed when the claim is made. */
    @Transactional
    public synchronized Sheet setGradeTerms(String id, String user, String grade, int terms) {
        Sheet sheet = load(id, user);
        GradeTerms t = gradeTerms.findById(new GradeTerms.Key(id, grade))
                .orElseGet(() -> new GradeTerms(id, grade, terms));
        t.setTerms(terms);
        gradeTerms.saveAndFlush(t);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=terms-" + grade + " value=" + terms);
        return sheet;
    }

    /** The percent of its figure an overdue claim on the sheet gains for every full 30 days it is
     *  overdue; 0.00 for a sheet never loaded. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String id) {
        return sheets.findById(id).map(Sheet::getLatePct).orElse(new BigDecimal("0.00"));
    }

    /** Change the sheet's late percent; recorded even when it is unchanged, then the figures it moves. */
    @Transactional
    public synchronized Sheet setLatePct(String id, String user, BigDecimal latePct) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setLatePct(latePct);
            Sheet saved = sheets.saveAndFlush(sheet);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=late-pct value="
                    + latePct.setScale(2).toPlainString());
            return saved;
        });
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

    /** The most a person's due-total on the sheet comes to; null when there is no cap. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String id) {
        return sheets.findById(id).map(Sheet::getCap).orElse(null);
    }

    /** Change the sheet's cap (null: no cap); recorded even when it is unchanged, then the figures
     *  it moves. */
    @Transactional
    public synchronized Sheet setCap(String id, String user, BigDecimal cap) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setCap(cap);
            Sheet saved = sheets.saveAndFlush(sheet);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=cap value="
                    + (cap == null ? "" : cap.setScale(2).toPlainString()));
            return saved;
        });
    }
}
