package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet never loaded before is started empty, its today the real date. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final ClaimSequenceRepository sequences;
    private final PeriodRepository periods;
    private final Rollup rollup;
    private final Audit audit;

    public SheetService(SheetRepository sheets, ClaimSequenceRepository sequences, PeriodRepository periods,
            Rollup rollup, Audit audit) {
        this.sheets = sheets;
        this.sequences = sequences;
        this.periods = periods;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(), LocalDate.now()));
            } catch (DataIntegrityViolationException e) {
                // Another user started the same sheet at the same moment.
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /** The sheet's today. Every date decision and default date in the sheet goes through this. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseThrow(SheetNotFoundException::new);
    }

    /** Change the sheet's today, recording the figures it moves. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findById(id).orElseThrow(SheetNotFoundException::new);
        Rollup.Snapshot before = rollup.take(id);
        sheet.setToday(today);
        rollup.recordChanges(id, user, before);
        return sheet;
    }

    /** Change the sheet's clerk limit: the largest due a clerk may settle; none for no limit. */
    @Transactional
    public Sheet setClerkLimit(String id, String user, BigDecimal limit) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        sheet.setClerkLimit(limit == null ? null : limit.setScale(2));
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=clerk-limit value="
                + (sheet.getClerkLimit() == null ? "" : sheet.getClerkLimit().toPlainString()));
        return sheet;
    }

    /** Change the sheet's floor: the least a claim's live due can be, recording the figures it moves. */
    @Transactional
    public Sheet setFloor(String id, String user, BigDecimal floor) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        Rollup.Snapshot before = rollup.take(id);
        sheet.setFloor(floor.setScale(2));
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=floor value="
                + sheet.getFloor().toPlainString());
        rollup.recordChanges(id, user, before);
        return sheet;
    }

    /** Change the sheet's terms: the number of days after a claim's date it is due by, for a claim added
     *  later whose grade has no terms of its own. A claim already added keeps its due-by. */
    @Transactional
    public Sheet setTerms(String id, String user, int terms) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        sheet.setTerms(terms);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=terms value=" + terms);
        return sheet;
    }

    /** Change a grade's own terms (none: it uses the sheet's terms), for a claim added later. A claim already
     *  added keeps its due-by. */
    @Transactional
    public Sheet setGradeTerms(String id, String user, String grade, Integer terms) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        sheet.setGradeTerms(grade, terms);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=terms-" + grade + " value="
                + (terms == null ? "" : terms.toString()));
        return sheet;
    }

    /** Change the sheet's late percent, recording the figures it moves. */
    @Transactional
    public Sheet setLatePct(String id, String user, BigDecimal latePct) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        Rollup.Snapshot before = rollup.take(id);
        sheet.setLatePct(latePct.setScale(2));
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=late-pct value="
                + sheet.getLatePct().toPlainString());
        rollup.recordChanges(id, user, before);
        return sheet;
    }

    /** Change the sheet's policy date (none: the sheet's today), recording the figures it moves. */
    @Transactional
    public Sheet setPolicyDate(String id, String user, LocalDate policyDate) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        Rollup.Snapshot before = rollup.take(id);
        sheet.setPolicyDate(policyDate);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=policy-date value="
                + (policyDate == null ? "" : policyDate.toString()));
        rollup.recordChanges(id, user, before);
        return sheet;
    }

    /** Change the sheet's period numbering from-date (none: off). */
    @Transactional
    public Sheet setPeriodNumberingFrom(String id, String user, LocalDate from) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        sheet.setPeriodNumberingFrom(from);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=period-numbering-from value="
                + (from == null ? "" : from.toString()));
        return sheet;
    }

    /** The next number for a claim dated on a date: while the sheet numbers by period, the next in the
     *  sequence of the period containing the date (<period name>-1, -2, ...), or of no period (X-1, ...);
     *  otherwise the sheet-wide one (1, 2, ...). None is ever given twice. */
    @Transactional
    public String nextClaimNumber(String id, LocalDate date) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        if (!sheet.numbersByPeriod()) {
            return sheet.nextClaimNumber();
        }
        String prefix = periods.findBySheetIdOrderByFromDateAsc(id).stream()
                .filter(p -> p.contains(date)).findFirst().map(Period::getName).orElse("X");
        ClaimSequence sequence = sequences.findBySheetIdAndPrefix(id, prefix)
                .orElseGet(() -> new ClaimSequence(id, prefix));
        String number = sequence.nextClaimNumber();
        sequences.save(sequence);
        return number;
    }
}
