package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.time.LocalDate;
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

/**
 * Keeps the sheet's settings: its clerk limit, its floor, its terms and each grade's, its late percent and its cap, each
 * dated (kept from a from-date); and its policy date and its period numbering from, not dated.
 */
@Service
public class SettingService {

    /** The grades that may have their own terms. */
    public static final List<String> GRADES = List.of("A", "B", "C");

    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;

    public SettingService(SheetRepository sheets, Audit audit, RollupService rollup) {
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    public Sheet get(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(() -> new SheetNotFoundException(sheetId));
    }

    /**
     * Set the clerk limit from the date (null: the sheet's today); a null limit means no limit. Every save is
     * recorded, even of the same value.
     */
    @Transactional
    public Sheet setClerkLimit(String sheetId, String user, BigDecimal limit, LocalDate from) {
        return record(sheetId, user, Sheet.CLERK_LIMIT, limit == null ? null : limit.toPlainString(), from, false);
    }

    /**
     * Set the floor, the least a live due of a claim dated from the date (null: the sheet's today) can be. Every save
     * is recorded, then the figures it moved.
     */
    @Transactional
    public Sheet setFloor(String sheetId, String user, BigDecimal floor, LocalDate from) {
        return record(sheetId, user, Sheet.FLOOR, floor.toPlainString(), from, true);
    }

    /**
     * Set the terms from the date (null: the sheet's today), the days after its date a claim dated from then of a grade
     * without its own terms is due by. Every save is recorded, even of the same value. Claims already added keep their
     * due-by.
     */
    @Transactional
    public Sheet setTerms(String sheetId, String user, int terms, LocalDate from) {
        return record(sheetId, user, Sheet.TERMS, String.valueOf(terms), from, false);
    }

    /** The grades with their own terms in force on the sheet's today, in grade order; a grade not in it uses the sheet's terms. */
    public Map<String, Integer> gradeTerms(Sheet sheet) {
        Map<String, Integer> terms = new LinkedHashMap<>();
        for (String grade : GRADES) {
            sheet.gradeTermsOn(grade, sheet.getToday()).ifPresent(t -> terms.put(grade, t));
        }
        return terms;
    }

    /** The terms in force for a claim dated on the date of a person of the grade: the grade's own, else the sheet's. */
    public int termsFor(String sheetId, String grade, LocalDate date) {
        return get(sheetId).termsFor(grade, date);
    }

    /**
     * Set a grade's own terms from the date (null: the sheet's today). Every save is recorded, even of the same value.
     * Claims already added keep their due-by.
     */
    @Transactional
    public Sheet setGradeTerms(String sheetId, String user, String grade, int terms, LocalDate from) {
        return record(sheetId, user, Sheet.gradeTermsKey(grade), String.valueOf(terms), from, false);
    }

    /**
     * Set the late percent from the date (null: the sheet's today), what an overdue claim dated from then gains for
     * every full 30 days it is overdue. Every save is recorded, then the figures it moved.
     */
    @Transactional
    public Sheet setLatePct(String sheetId, String user, BigDecimal latePct, LocalDate from) {
        return record(sheetId, user, Sheet.LATE_PCT, latePct.toPlainString(), from, true);
    }

    /**
     * Set the cap from the date (null: the sheet's today), the most a person's due-total can be; a null cap means
     * none. Every save is recorded, then the figures it moved.
     */
    @Transactional
    public Sheet setCap(String sheetId, String user, BigDecimal cap, LocalDate from) {
        return record(sheetId, user, Sheet.CAP, cap == null ? null : cap.toPlainString(), from, true);
    }

    /**
     * Record a dated setting's value from the date (null: the sheet's today), replacing one recorded from that date,
     * then the SETTING_CHANGED record (with the from-date when one was given) and, when the setting moves figures,
     * the figures it moved.
     */
    private Sheet record(String sheetId, String user, String key, String value, LocalDate from, boolean movesFigures) {
        Sheet sheet = get(sheetId);
        RollupService.Snapshot before = movesFigures ? rollup.snapshot(sheetId) : null;
        sheet.record(key, from != null ? from : sheet.getToday(), value);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + key + " value="
                + (value == null ? "" : value) + (from == null ? "" : " from=" + from));
        if (movesFigures) {
            rollup.recordChanges(sheetId, user, before);
        }
        return saved;
    }
    /**
     * Set the policy date, which decides how each claim's live figures round; null means the sheet's today. Every save
     * is recorded, even of the same value, then the figures it moved.
     */
    @Transactional
    public Sheet setPolicyDate(String sheetId, String user, LocalDate policyDate) {
        Sheet sheet = get(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setPolicyDate(policyDate);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=policy-date value="
                + (policyDate == null ? "" : policyDate.toString()));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Set the period numbering from; null means off. Every save is recorded, even of the same value. Claims already
     * opened keep their numbers.
     */
    @Transactional
    public Sheet setPeriodNumberingFrom(String sheetId, String user, LocalDate from) {
        Sheet sheet = get(sheetId);
        sheet.setPeriodNumberingFrom(from);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=period-numbering-from value="
                + (from == null ? "" : from.toString()));
        return saved;
    }
}
