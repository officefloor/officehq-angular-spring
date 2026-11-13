package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.PersonSnapshot;
import net.officefloor.hq.app.person.PersonSnapshotRepository;
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
    private final FiscalClaimSequenceRepository fiscalSequences;
    private final PeriodRepository periods;
    private final PeriodService periodService;
    private final Rollup rollup;
    private final RoleService roles;
    private final Audit audit;
    private final ClaimRepository claims;
    private final History history;
    private final PersonSnapshotRepository snapshots;

    public SheetService(SheetRepository sheets, ClaimSequenceRepository sequences,
            FiscalClaimSequenceRepository fiscalSequences, PeriodRepository periods,
            PeriodService periodService, Rollup rollup, RoleService roles, Audit audit, ClaimRepository claims,
            History history, PersonSnapshotRepository snapshots) {
        this.snapshots = snapshots;
        this.fiscalSequences = fiscalSequences;
        this.history = history;
        this.claims = claims;
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

    /** The sheet's pot; none when there is no pot. */
    @Transactional(readOnly = true)
    public BigDecimal pot(String id) {
        return sheets.findById(id).orElseThrow(SheetNotFoundException::new).getPot();
    }

    /** The sheet's today. Every date decision and default date in the sheet goes through this. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseThrow(SheetNotFoundException::new);
    }

    /** The default date of a new claim: the sheet's entry date while set, else its today. */
    @Transactional(readOnly = true)
    public LocalDate entryDefault(String id) {
        return sheets.findById(id).map(sheet -> sheet.getEntryDate() != null ? sheet.getEntryDate() : sheet.getToday()).orElseThrow(SheetNotFoundException::new);
    }

    /** Change the sheet's today, closing the periods past their grace days and recording the figures it
     *  moves; a new today first records a SNAPSHOT line of each person's due-total on the day being left, in
     *  list order, keeps it as each person's snapshot line, and releases every claim's lock (their editors
     *  close). */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        Rollup.Snapshot before = rollup.take(id);
        if (!today.equals(sheet.getToday())) {
            rollup.recordSnapshot(id, user, before);
            LocalDate left = sheet.getToday();
            before.people().forEach((personId, figures) -> snapshots.save(
                    new PersonSnapshot(id, personId, left, figures.dueTotal())));
            claims.unlockAll(id);
        }
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

    /** Record a grade's own proof threshold from a date (none: the sheet's today; no threshold: the grade uses
     *  the sheet's): a claim whose person's grade in force on its date has one in force then needs a proof to
     *  be settled or approved when its due is above it. */
    @Transactional
    public Sheet setGradeProofThreshold(String id, String user, String grade, BigDecimal threshold, LocalDate from) {
        return setDated(id, user, Sheet.PROOF_THRESHOLD + "-" + grade,
                threshold == null ? null : threshold.setScale(2).toPlainString(), from, false);
    }

    /** Record the sheet's note threshold from a date (none: the sheet's today): a claim dated then whose due
     *  is above it cannot be opened with an empty note; none for no threshold. */
    @Transactional
    public Sheet setNoteThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return setDated(id, user, Sheet.NOTE_THRESHOLD, threshold == null ? null : threshold.setScale(2).toPlainString(),
                from, false);
    }

    /** Record the sheet's review threshold from a date (none: the sheet's today): a claim dated then whose
     *  due is above it goes to REVIEW when opened; none for no threshold. */
    @Transactional
    public Sheet setReviewThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return setDated(id, user, Sheet.REVIEW_THRESHOLD,
                threshold == null ? null : threshold.setScale(2).toPlainString(), from, false);
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

    /** Record a grade's grace days from a date (none: the sheet's today; no days: the grade has none): a
     *  claim whose person's grade in force on its date has grace days in force then is overdue only once its
     *  due-by plus them is before the sheet's today, its late charge counted from there; recording the figures
     *  it moves. A claim's due-by stays as it is. */
    @Transactional
    public Sheet setGradeGrace(String id, String user, String grade, Integer days, LocalDate from) {
        return setDated(id, user, Sheet.GRACE + "-" + grade, days == null ? null : days.toString(), from, true);
    }

    /** Record the sheet's rounding mode (half-up or half-even; none: rounding by the policy date) from a date
     *  (none: the sheet's today), recording the figures it moves. */
    @Transactional
    public Sheet setRounding(String id, String user, String mode, LocalDate from) {
        return setDated(id, user, Sheet.ROUNDING, mode, from, true);
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
        history.keep(sheet);
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
        history.keep(sheet);
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

    /** Change the sheet's fiscal start month (1 to 12). Not dated. */
    @Transactional
    public Sheet setFiscalStartMonth(String id, String user, int month) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        sheet.setFiscalStartMonth(month);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=fiscal-start-month value=" + month);
        return sheet;
    }

    /** Change the sheet's pot (none: no pot). Not dated. */
    @Transactional
    public Sheet setPot(String id, String user, BigDecimal pot) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        BigDecimal p = pot == null ? null : pot.setScale(2);
        sheet.setPot(p);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=pot value="
                + (p == null ? "" : p.toPlainString()));
        return sheet;
    }

    /** Change the sheet's alert threshold (none: off). Not dated. */
    @Transactional
    public Sheet setAlertThreshold(String id, String user, BigDecimal threshold) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        BigDecimal t = threshold == null ? null : threshold.setScale(2);
        sheet.setAlertThreshold(t);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=alert-threshold value="
                + (t == null ? "" : t.toPlainString()));
        return sheet;
    }

    /** Change the sheet's parent (none: no parent), recording the figures it moves. Not dated. The sheet
     *  cannot be its own parent, nor name a sheet that has it as an ancestor, and the parent must exist. */
    @Transactional
    public Sheet setParent(String id, String user, String parent) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        String p = parent == null || parent.isBlank() ? null : parent.trim();
        if (p != null) {
            if (p.equals(id)) {
                throw new ParentRefusedException("A sheet cannot be its own parent.");
            }
            if (!sheets.existsById(p)) {
                throw new ParentRefusedException("The parent must be a sheet that has been loaded.");
            }
            if (hasAncestor(p, id)) {
                throw new ParentRefusedException("The parent cannot be a sheet that has this sheet as an ancestor.");
            }
        }
        Rollup.Snapshot before = rollup.take(id);
        sheet.setParent(p);
        sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=parent value="
                + (p == null ? "" : p));
        rollup.recordChanges(id, user, before);
        return sheet;
    }

    /** Whether a sheet has another among all its ancestors, however far up. */
    private boolean hasAncestor(String id, String ancestor) {
        Set<String> seen = new HashSet<>();
        String at = sheets.findById(id).map(Sheet::getParent).orElse(null);
        while (at != null && seen.add(at)) {
            if (at.equals(ancestor)) {
                return true;
            }
            at = sheets.findById(at).map(Sheet::getParent).orElse(null);
        }
        return false;
    }

    /** Change the sheet's entry date (none: off, a new claim takes the sheet's today). Not dated. */
    @Transactional
    public Sheet setEntryDate(String id, String user, LocalDate entryDate) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        sheet.setEntryDate(entryDate);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=entry-date value="
                + (entryDate == null ? "" : entryDate.toString()));
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

    /** The sheet's next settle batch number (1, 2, ...). None is ever given twice. */
    @Transactional
    public int nextSettleBatch(String id) {
        return sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new).nextSettleBatch();
    }

    /** The next number for a claim due by a date: while the sheet numbers by period, the next in the
     *  sequence of the period containing the date (<period name>-1, -2, ...), or of no period (X-1, ...);
     *  otherwise the sheet-wide one, which restarts each fiscal year (FY<year>-1, -2, ..., the fiscal year
     *  the date falls in by the sheet's fiscal start month) and which the sheets of a tree share: the next
     *  after the last given in that fiscal year on any of them. None is ever given twice. */
    @Transactional
    public String nextClaimNumber(String id, LocalDate date) {
        Sheet sheet = sheets.findForUpdateById(id).orElseThrow(SheetNotFoundException::new);
        if (!sheet.numbersByPeriod()) {
            // lock the others of the tree in id order, so two users on the tree number one after the other
            List<Sheet> tree = sheets.tree(id).stream().sorted()
                    .map(other -> sheets.findForUpdateById(other).orElse(null))
                    .filter(Objects::nonNull).toList();
            int fiscalYear = sheet.fiscalYearOf(date);
            List<String> others = tree.stream().map(Sheet::getId).toList();
            int lastOfTree = fiscalSequences.findBySheetIdInAndFiscalYear(others, fiscalYear).stream()
                    .mapToInt(FiscalClaimSequence::getLastNumber).max().orElse(0);
            FiscalClaimSequence sequence = fiscalSequences.findBySheetIdAndFiscalYear(id, fiscalYear)
                    .orElseGet(() -> new FiscalClaimSequence(id, fiscalYear));
            String number = sequence.nextClaimNumber(lastOfTree);
            fiscalSequences.save(sequence);
            return number;
        }
        String prefix = periodPrefix(id, date);
        ClaimSequence sequence = sequences.findBySheetIdAndPrefix(id, prefix)
                .orElseGet(() -> new ClaimSequence(id, prefix));
        String number = sequence.nextClaimNumber();
        sequences.save(sequence);
        return number;
    }

    /** The prefix of the numbering sequence of a date: the name of the period containing it, else X. */
    private String periodPrefix(String id, LocalDate date) {
        return periods.findBySheetIdOrderByFromDateAsc(id).stream()
                .filter(p -> p.contains(date)).findFirst().map(Period::getName).orElse("X");
    }
}
