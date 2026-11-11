package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the sheet's settings: its clerk limit, its floor, its terms, its late percent and its policy date. */
@Service
public class SettingService {

    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final GradeTermsRepository gradeTerms;

    public SettingService(SheetRepository sheets, Audit audit, RollupService rollup, GradeTermsRepository gradeTerms) {
        this.sheets = sheets;
        this.gradeTerms = gradeTerms;
        this.audit = audit;
        this.rollup = rollup;
    }

    public Sheet get(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(() -> new SheetNotFoundException(sheetId));
    }

    /** Set the clerk limit; null means no limit. Every save is recorded, even of the same value. */
    @Transactional
    public Sheet setClerkLimit(String sheetId, String user, BigDecimal limit) {
        Sheet sheet = get(sheetId);
        sheet.setClerkLimit(limit);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=clerk-limit value="
                + (limit == null ? "" : limit.toPlainString()));
        return saved;
    }

    /** Set the floor, the least a live due can be. Every save is recorded, then the figures it moved. */
    @Transactional
    public Sheet setFloor(String sheetId, String user, BigDecimal floor) {
        Sheet sheet = get(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setFloor(floor);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=floor value="
                + floor.toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Set the terms, the days after its date a claim of a grade without its own terms is due by. Every save is
     * recorded, even of the same value. Claims already added keep their due-by.
     */
    @Transactional
    public Sheet setTerms(String sheetId, String user, int terms) {
        Sheet sheet = get(sheetId);
        sheet.setTerms(terms);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=terms value=" + terms);
        return saved;
    }

    /** The grades with their own terms, in grade order; a grade not in it uses the sheet's terms. */
    public Map<String, Integer> gradeTerms(String sheetId) {
        Map<String, Integer> terms = new LinkedHashMap<>();
        gradeTerms.findBySheetIdOrderByGrade(sheetId).forEach(t -> terms.put(t.getGrade(), t.getTerms()));
        return terms;
    }

    /** The terms in force for a claim of a person of the grade: the grade's own, else the sheet's. */
    public int termsFor(String sheetId, String grade) {
        return gradeTerms.findBySheetIdAndGrade(sheetId, grade).map(GradeTerms::getTerms)
                .orElseGet(() -> get(sheetId).getTerms());
    }

    /**
     * Set a grade's own terms. Every save is recorded, even of the same value. Claims already added keep their
     * due-by.
     */
    @Transactional
    public Sheet setGradeTerms(String sheetId, String user, String grade, int terms) {
        Sheet sheet = get(sheetId);
        GradeTerms kept = gradeTerms.findBySheetIdAndGrade(sheetId, grade)
                .orElseGet(() -> new GradeTerms(sheetId, grade, terms));
        kept.setTerms(terms);
        gradeTerms.saveAndFlush(kept);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=terms-" + grade + " value="
                + terms);
        return sheet;
    }

    /**
     * Set the late percent, what an overdue claim's due gains for every full 30 days it is overdue. Every save is
     * recorded, then the figures it moved.
     */
    @Transactional
    public Sheet setLatePct(String sheetId, String user, BigDecimal latePct) {
        Sheet sheet = get(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setLatePct(latePct);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=late-pct value="
                + latePct.toPlainString());
        rollup.recordChanges(sheetId, user, before);
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
