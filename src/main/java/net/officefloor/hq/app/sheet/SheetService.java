package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.role.ActionNotAllowedException;
import net.officefloor.hq.app.role.RoleService;
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
    private final PeriodService periodService;
    private final Rollup rollup;
    private final RoleService roles;
    private final Audit audit;

    public SheetService(SheetRepository sheets, ClaimSequenceRepository sequences, PeriodRepository periods,
            PeriodService periodService, Rollup rollup, RoleService roles, Audit audit) {
        this.sheets = sheets;
        this.sequences = sequences;
        this.periods = periods;
        this.periodService = periodService;
        this.rollup = rollup;
        this.roles = roles;
        this.audit = audit;
    }

    /** Whether the sheet is archived; a sheet never loaded is not. */
    @Transactional(readOnly = true)
    public boolean isArchived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }

    /** Refuse any change to an archived sheet. */
    public void requireNotArchived(String id) {
        if (isArchived(id)) {
            throw new SheetArchivedException();
        }
    }

    /** A steward archives the sheet: everything stays visible and every change is refused from then on.
     *  Anyone but a steward is refused, as is a sheet already archived. */
    @Transactional
    public Sheet archive(String id, String user) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        if (sheet.isArchived()) {
            throw new SheetArchivedException();
        }
        if (!roles.isSteward(id, user)) {
            throw new ActionNotAllowedException("archive the sheet");
        }
        sheet.archive();
        sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + user);
        return sheet;
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

    /** Change the sheet's today, closing the periods past their grace days and recording the figures it
     *  moves. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        Rollup.Snapshot before = rollup.take(id);
        sheet.setToday(today);
        sheets.saveAndFlush(sheet);
        periodService.closeOverdue(id, user, today, sheet.getGraceDays());
        rollup.recordChanges(id, user, before);
        return sheet;
    }

    /** Record the sheet's clerk limit from a date (none: the sheet's today): the largest due a clerk may
     *  settle; none for no limit. */
    @Transactional
    public Sheet setClerkLimit(String id, String user, BigDecimal limit, LocalDate from) {
        return setDated(id, user, Sheet.CLERK_LIMIT, limit == null ? null : limit.setScale(2).toPlainString(), from,
                false);
    }

    /** Record the sheet's floor from a date (none: the sheet's today): the least a claim's live due can be,
     *  recording the figures it moves. */
    @Transactional
    public Sheet setFloor(String id, String user, BigDecimal floor, LocalDate from) {
        return setDated(id, user, Sheet.FLOOR, floor.setScale(2).toPlainString(), from, true);
    }

    /** Record the sheet's cap from a date (none: the sheet's today): the most a person's due-total can be
     *  (none: no cap), recording the figures it moves. */
    @Transactional
    public Sheet setCap(String id, String user, BigDecimal cap, LocalDate from) {
        return setDated(id, user, Sheet.CAP, cap == null ? null : cap.setScale(2).toPlainString(), from, true);
    }

    /** Record the sheet's proof threshold from a date (none: the sheet's today): a claim dated then whose due
     *  is above it needs a proof to be settled or approved; none for no threshold. */
    @Transactional
    public Sheet setProofThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return setDated(id, user, Sheet.PROOF_THRESHOLD, threshold == null ? null : threshold.setScale(2).toPlainString(),
                from, false);
    }

    /** Record the sheet's terms from a date (none: the sheet's today): the number of days after a claim's
     *  date it is due by, for a claim added later dated then whose grade has no terms of its own. A claim
     *  already added keeps its due-by. */
    @Transactional
    public Sheet setTerms(String id, String user, int terms, LocalDate from) {
        return setDated(id, user, Sheet.TERMS, Integer.toString(terms), from, false);
    }

    /** Record a grade's own terms from a date (none: the sheet's today; no terms: it uses the sheet's
     *  terms), for a claim added later. A claim already added keeps its due-by. */
    @Transactional
    public Sheet setGradeTerms(String id, String user, String grade, Integer terms, LocalDate from) {
        return setDated(id, user, Sheet.TERMS + "-" + grade, terms == null ? null : terms.toString(), from, false);
    }

    /** Record the sheet's late percent from a date (none: the sheet's today), recording the figures it
     *  moves. */
    @Transactional
    public Sheet setLatePct(String id, String user, BigDecimal latePct, LocalDate from) {
        return setDated(id, user, Sheet.LATE_PCT, latePct.setScale(2).toPlainString(), from, true);
    }

    /** Record a dated setting's value (none: blank) from a date, the sheet's today when none is given,
     *  replacing one already from that date; when it can move figures, record those that moved. */
    private Sheet setDated(String id, String user, String key, String value, LocalDate from, boolean movesFigures) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        Rollup.Snapshot before = movesFigures ? rollup.take(id) : null;
        sheet.setSetting(key, from == null ? sheet.getToday() : from, value);
        sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=" + key + " value="
                + (value == null ? "" : value) + (from == null ? "" : " from=" + from));
        if (movesFigures) {
            rollup.recordChanges(id, user, before);
        }
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

    /** Set the roles that may take an action (open, settle, void, reassign, close or reopen), comma
     *  separated; blank keeps the standing rule. Not dated. */
    @Transactional
    public Sheet setPermission(String id, String user, String action, String roles) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        String value = roles == null ? "" : roles.trim();
        sheet.setPermission(action, value);
        sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=perm-" + action + " value=" + value);
        return sheet;
    }

    /** Change the sheet's grace days (none: off). Not dated. */
    @Transactional
    public Sheet setGraceDays(String id, String user, Integer graceDays) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        sheet.setGraceDays(graceDays);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=grace-days value="
                + (graceDays == null ? "" : graceDays.toString()));
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
        String prefix = periodPrefix(id, date);
        ClaimSequence sequence = sequences.findBySheetIdAndPrefix(id, prefix)
                .orElseGet(() -> new ClaimSequence(id, prefix));
        String number = sequence.nextClaimNumber();
        sequences.save(sequence);
        return number;
    }

    /** The number a claim keeps when re-dated from one date to another: while the sheet numbers by period
     *  and the new date is in another period (or no period, X) than the old, the next in the new period's
     *  sequence (the old number is never given again); otherwise the number it has. */
    @Transactional
    public String redatedClaimNumber(String id, String number, LocalDate from, LocalDate to) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        if (number == null || !sheet.numbersByPeriod() || periodPrefix(id, from).equals(periodPrefix(id, to))) {
            return number;
        }
        return nextClaimNumber(id, to);
    }

    /** The prefix of the numbering sequence of a date: the name of the period containing it, else X. */
    private String periodPrefix(String id, LocalDate date) {
        return periods.findBySheetIdOrderByFromDateAsc(id).stream()
                .filter(p -> p.contains(date)).findFirst().map(Period::getName).orElse("X");
    }
}
