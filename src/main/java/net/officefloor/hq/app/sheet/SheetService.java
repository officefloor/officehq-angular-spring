package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.claim.ProofKind;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SheetService {

    private final SheetRepository sheets;
    private final SheetFiscalNumberRepository fiscalNumbers;
    private final RollupService rollup;
    private final ApplicationEventPublisher events;

    public SheetService(SheetRepository sheets, SheetFiscalNumberRepository fiscalNumbers, RollupService rollup, ApplicationEventPublisher events) {
        this.sheets = sheets;
        this.fiscalNumbers = fiscalNumbers;
        this.rollup = rollup;
        this.events = events;
    }

    /** Load a sheet by id; an id never used before starts an empty sheet, its today the real date. */
    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, LocalDate.now()));
            } catch (DataIntegrityViolationException e) {
                // another user created it at the same moment
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /** The sheet, if it was ever loaded. */
    @Transactional(readOnly = true)
    public Optional<Sheet> find(String id) {
        return sheets.findById(id);
    }

    /** Write the changes made to a loaded sheet. */
    @Transactional
    public void flush() {
        sheets.flush();
    }

    /** The sheet's today: the date every date decision and default date on the sheet uses. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(LocalDate::now);
    }

    /** The date a new claim left undated takes: the sheet's entry date while set, else its today. */
    @Transactional(readOnly = true)
    public LocalDate entryDate(String id) {
        return sheets.findById(id).map(Sheet::getEntryDateInForce).orElseGet(LocalDate::now);
    }

    /** The sheet's floor in force on its today. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String id) {
        return sheets.findById(id).map(Sheet::getFloor).orElse(Sheet.DEFAULT_FLOOR);
    }

    /** The sheet's floor in force on a date: a live due of a claim dated then is at least it. */
    @Transactional(readOnly = true)
    public BigDecimal floorOn(String id, LocalDate on) {
        return sheets.findById(id).map(s -> s.floorOn(on)).orElse(Sheet.DEFAULT_FLOOR);
    }

    /** The sheet's terms in force on its today. */
    @Transactional(readOnly = true)
    public int terms(String id) {
        return sheets.findById(id).map(Sheet::getTerms).orElse(Sheet.DEFAULT_TERMS);
    }

    /** The sheet's late percent in force on its today. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String id) {
        return sheets.findById(id).map(Sheet::getLatePct).orElse(Sheet.DEFAULT_LATE_PCT);
    }

    /** The sheet's cap in force on its today: a person's due-total is at most it; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String id) {
        return sheets.findById(id).map(Sheet::getCap).orElse(null);
    }

    /** The sheet's proof threshold in force on a date; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThresholdOn(String id, LocalDate on) {
        return sheets.findById(id).map(s -> s.proofThresholdOn(on)).orElse(null);
    }

    /** The grade a new person is given when none is named: the sheet's default grade, else A (A
     *  too while the default grade is retired). */
    @Transactional(readOnly = true)
    public String defaultGrade(String id) {
        return sheets.findById(id).filter(s -> s.getDefaultGrade() != null && !s.isGradeRetired(s.getDefaultGrade()))
                .map(Sheet::getDefaultGrade).orElse("A");
    }

    /** Refuse (422) choosing a grade retired on the sheet. */
    @Transactional(readOnly = true)
    public void requireActiveGrade(String id, String grade) {
        if (sheets.findById(id).map(s -> s.isGradeRetired(grade)).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Grade " + grade + " is retired");
        }
    }

    /** The sheet's proof kinds, each of which a claim above the proof threshold needs a proof of;
     *  none when blank. */
    @Transactional(readOnly = true)
    public List<ProofKind> proofKinds(String id) {
        String kinds = sheets.findById(id).map(Sheet::getProofKinds).orElse(null);
        return kinds == null ? List.of() : Arrays.stream(kinds.split(","))
                .map(k -> ProofKind.of(k).orElseThrow()).toList();
    }

    /** The proof threshold in force on a date for a grade: the grade's own when one is set, else
     *  the sheet's; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal proofThresholdFor(String id, String grade, LocalDate on) {
        return sheets.findById(id).map(s -> s.proofThresholdFor(grade, on)).orElse(null);
    }

    /** The sheet's note threshold in force on a date; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal noteThresholdOn(String id, LocalDate on) {
        return sheets.findById(id).map(s -> s.noteThresholdOn(on)).orElse(null);
    }

    /** The sheet's review threshold in force on a date; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal reviewThresholdOn(String id, LocalDate on) {
        return sheets.findById(id).map(s -> s.reviewThresholdOn(on)).orElse(null);
    }

    /** The ids of the other sheets of the sheet's tree, among those that exist: its ancestors (its
     *  parent, theirs, and so on) and its descendants (its children, theirs, and so on). */
    @Transactional(readOnly = true)
    public Set<String> tree(String id) {
        Set<String> seen = new LinkedHashSet<>(Set.of(id));
        for (String at = sheets.findById(id).map(Sheet::getParent).orElse(null);
                at != null && sheets.existsById(at) && seen.add(at);
                at = sheets.findById(at).map(Sheet::getParent).orElse(null)) {
        }
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(id));
        while (!todo.isEmpty()) {
            for (Sheet child : sheets.findByParentOrderByIdAsc(todo.poll())) {
                if (seen.add(child.getId())) {
                    todo.add(child.getId());
                }
            }
        }
        seen.remove(id);
        return seen;
    }

    /** The ids of the sheet's descendants among those that exist (its children, theirs, and so
     *  on), in id order. */
    @Transactional(readOnly = true)
    public List<String> descendants(String id) {
        Set<String> seen = new LinkedHashSet<>(Set.of(id));
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(id));
        while (!todo.isEmpty()) {
            for (Sheet child : sheets.findByParentOrderByIdAsc(todo.poll())) {
                if (seen.add(child.getId())) {
                    todo.add(child.getId());
                }
            }
        }
        seen.remove(id);
        return seen.stream().sorted().toList();
    }

    /** Whether the sheet is archived: every change on it is refused. */
    @Transactional(readOnly = true)
    public boolean archived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }

    /** Whether a claim opened now is numbered within its period. */
    @Transactional(readOnly = true)
    public boolean periodNumberingOn(String id) {
        return sheets.findById(id).map(Sheet::isPeriodNumberingOn).orElse(false);
    }

    /** Take the sheet's next claim number for a claim due by {@code date}: FY&lt;yyyy&gt;-&lt;n&gt;, counting
     *  from 1 within the fiscal year the date falls in (by the sheet's fiscal start month, named by
     *  the calendar year its first day falls in). The sheets of a tree share one sequence per fiscal
     *  year, so it is the next after the last given in that year on any sheet of the tree, holding
     *  the sheets locked until the transaction ends so numbers are never shared and, if it rolls
     *  back, never skipped. */
    @Transactional
    public String nextFiscalClaimNumber(String id, LocalDate date) {
        Sheet sheet = sheets.findForUpdate(id).orElseThrow();
        int year = fiscalYear(date, sheet.getFiscalStartMonth());
        List<String> tree = tree(id).stream().sorted().toList();
        tree.forEach(sheets::findForUpdate);
        int last = tree.stream()
                .map(s -> fiscalNumbers.findBySheetIdAndFiscalYear(s, year))
                .flatMap(Optional::stream)
                .mapToInt(SheetFiscalNumber::getLastNumber)
                .max().orElse(0);
        SheetFiscalNumber counter = fiscalNumbers.findBySheetIdAndFiscalYear(id, year)
                .orElseGet(() -> new SheetFiscalNumber(id, year));
        int number = counter.nextNumberAfter(last);
        fiscalNumbers.saveAndFlush(counter);
        return "FY" + year + "-" + number;
    }

    /** The fiscal year a date falls in, named by the calendar year its first day falls in. */
    static int fiscalYear(LocalDate date, int startMonth) {
        return date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
    }

    /** Hold the sheet locked until the transaction ends, so changes checked against the rest of
     *  the sheet (such as a period that must not overlap another) are made one at a time. */
    @Transactional
    public void lock(String id) {
        sheets.findForUpdate(id).orElseThrow();
    }

    /** Change the sheet's today (the sheet is created first if it was never loaded); it has no
     *  audit line of its own; when it moves, each person's due-total on the day being left is
     *  recorded first (SNAPSHOT), preceded while the score log is on by each person's score on that
     *  day (SCORE), then what follows from it (such as periods closed past their
     *  grace days) and then the figures it moves. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        lock(id);
        RollupService.Snapshot before = rollup.snapshot(id);
        if (!today.equals(sheet.getToday())) {
            if (sheet.isScoreLog()) {
                rollup.recordScores(id, user, before);
            }
            rollup.recordSnapshot(id, user, sheet.getToday(), before);
        }
        sheet.setToday(today);
        sheets.flush();
        events.publishEvent(new TodayChanged(id, user, today));
        rollup.recordChanges(id, user, before);
        return sheet;
    }
}
