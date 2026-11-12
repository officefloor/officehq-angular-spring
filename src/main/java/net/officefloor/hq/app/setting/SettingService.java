package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the sheet's settings: its clerk limit, its floor, its terms and each grade's, each grade's grace days, its late percent, its cap, its proof threshold and each grade's, its rounding mode, each
 * dated (kept from a from-date); and its policy date, its period numbering from, its entry date, its grace days, its fiscal year start month, its pot, its alert threshold, its parent sheet and
 * its permission matrix, not dated.
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
     * Set a grade's grace days from the date (null: the sheet's today); null means none. A claim whose person's grade on
     * its date has grace days then goes overdue, and counts its late charge, from its due-by plus them. Every save is
     * recorded, then the figures it moved.
     */
    @Transactional
    public Sheet setGradeGrace(String sheetId, String user, String grade, Integer days, LocalDate from) {
        return record(sheetId, user, Sheet.gradeGraceKey(grade), days == null ? null : days.toString(), from, true);
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
     * Set the proof threshold from the date (null: the sheet's today): a claim dated from then whose due is above it
     * cannot be settled or approved without a proof; a null threshold means none. Every save is recorded, even of the
     * same value.
     */
    @Transactional
    public Sheet setProofThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        return record(sheetId, user, Sheet.PROOF_THRESHOLD, threshold == null ? null : threshold.toPlainString(), from,
                false);
    }

    /**
     * Set a grade's own proof threshold from the date (null: the sheet's today): a claim dated from then of a person
     * of the grade (on its date) is weighed against it in place of the sheet's; a null threshold means none (the
     * sheet's applies). Every save is recorded, even of the same value.
     */
    @Transactional
    public Sheet setGradeProofThreshold(String sheetId, String user, String grade, BigDecimal threshold,
            LocalDate from) {
        return record(sheetId, user, Sheet.gradeProofThresholdKey(grade),
                threshold == null ? null : threshold.toPlainString(), from, false);
    }

    /**
     * Set the note threshold from the date (null: the sheet's today): a claim dated from then whose due is above it
     * cannot be opened with an empty note; a null threshold means none. Every save is recorded, even of the same value.
     */
    @Transactional
    public Sheet setNoteThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        return record(sheetId, user, Sheet.NOTE_THRESHOLD, threshold == null ? null : threshold.toPlainString(), from,
                false);
    }

    /**
     * Set the review threshold from the date (null: the sheet's today): a claim dated from then whose due is above it
     * goes to REVIEW when opened instead of OPEN; a null threshold means none. Every save is recorded, even of the
     * same value.
     */
    @Transactional
    public Sheet setReviewThreshold(String sheetId, String user, BigDecimal threshold, LocalDate from) {
        return record(sheetId, user, Sheet.REVIEW_THRESHOLD, threshold == null ? null : threshold.toPlainString(),
                from, false);
    }

    /**
     * Set the rounding mode (half-up or half-even) from the date (null: the sheet's today); null means none (a claim
     * dated then rounds by the policy date). A claim whose date has a mode in force rounds every live figure by it.
     * Every save is recorded, then the figures it moved.
     */
    @Transactional
    public Sheet setRounding(String sheetId, String user, String mode, LocalDate from) {
        return record(sheetId, user, Sheet.ROUNDING, mode, from, true);
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
     * Set the roles (comma separated) that may take the action; null or blank keeps the standing rule. Every save is
     * recorded, even of the same value.
     */
    @Transactional
    public Sheet setPermission(String sheetId, String user, String action, String roles) {
        Sheet sheet = get(sheetId);
        sheet.setPermission(action, roles);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=perm-" + action + " value="
                + (roles == null ? "" : roles));
        return saved;
    }

    /** Set the grace days; null means off. Every save is recorded, even of the same value. */
    @Transactional
    public Sheet setGraceDays(String sheetId, String user, Integer graceDays) {
        Sheet sheet = get(sheetId);
        sheet.setGraceDays(graceDays);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=grace-days value="
                + (graceDays == null ? "" : graceDays.toString()));
        return saved;
    }

    /** Set the escalation gap in days; null means off. Every save is recorded, even of the same value. */
    @Transactional
    public Sheet setEscalateAfter(String sheetId, String user, Integer days) {
        Sheet sheet = get(sheetId);
        sheet.setEscalateAfter(days);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=escalate-after value="
                + (days == null ? "" : days.toString()));
        return saved;
    }

    /** Set the fiscal year start month (1 to 12). Every save is recorded, even of the same value. */
    @Transactional
    public Sheet setFiscalStartMonth(String sheetId, String user, int month) {
        Sheet sheet = get(sheetId);
        sheet.setFiscalStartMonth(month);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=fiscal-start-month value=" + month);
        return saved;
    }

    /** Set the pot, shared out over each balanced pool's members; null means none. Every save is recorded, even of the same value. */
    @Transactional
    public Sheet setPot(String sheetId, String user, BigDecimal pot) {
        Sheet sheet = get(sheetId);
        sheet.setPot(pot);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=pot value="
                + (pot == null ? "" : pot.toPlainString()));
        return saved;
    }

    /**
     * Set the alert threshold, above which a person's due-total going is reported in an ALERT line; null means off.
     * Every save is recorded, even of the same value.
     */
    @Transactional
    public Sheet setAlertThreshold(String sheetId, String user, BigDecimal threshold) {
        Sheet sheet = get(sheetId);
        sheet.setAlertThreshold(threshold);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=alert-threshold value="
                + (threshold == null ? "" : threshold.toPlainString()));
        return saved;
    }

    /**
     * Set the parent sheet, whose rates a grade with none in force on a date here falls back on; null means none.
     * Refused for the sheet itself, a sheet that has this sheet as an ancestor, and a sheet never loaded. Every save
     * is recorded, even of the same value, then the figures it moved.
     */
    @Transactional
    public Sheet setParent(String sheetId, String user, String parent) {
        Sheet sheet = get(sheetId);
        if (parent != null) {
            if (parent.equals(sheetId)) {
                throw new ParentRefusedException(sheetId, parent, "a sheet cannot be its own parent");
            }
            Sheet ancestor = sheets.findById(parent)
                    .orElseThrow(() -> new ParentRefusedException(sheetId, parent, "it has never been loaded"));
            Set<String> seen = new HashSet<>();
            while (ancestor != null && seen.add(ancestor.getId())) {
                if (sheetId.equals(ancestor.getParent())) {
                    throw new ParentRefusedException(sheetId, parent, "it has this sheet as an ancestor");
                }
                ancestor = ancestor.getParent() == null ? null : sheets.findById(ancestor.getParent()).orElse(null);
            }
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setParent(parent);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=parent value="
                + (parent == null ? "" : parent));
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

    /**
     * Set the entry date, the default date of a new claim in place of the sheet's today; null means off. Every save is
     * recorded, even of the same value. Claims already added keep their dates.
     */
    @Transactional
    public Sheet setEntryDate(String sheetId, String user, LocalDate entryDate) {
        Sheet sheet = get(sheetId);
        sheet.setEntryDate(entryDate);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=entry-date value="
                + (entryDate == null ? "" : entryDate.toString()));
        return saved;
    }
}
