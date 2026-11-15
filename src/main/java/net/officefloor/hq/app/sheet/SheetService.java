package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;
    private final ApplicationEventPublisher events;

    public SheetService(SheetRepository sheets, RollupService rollup, ApplicationEventPublisher events) {
        this.sheets = sheets;
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

    /** The sheet's note threshold in force on a date; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal noteThresholdOn(String id, LocalDate on) {
        return sheets.findById(id).map(s -> s.noteThresholdOn(on)).orElse(null);
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

    /** Take the sheet's next claim number (1 for the first), holding the sheet locked until the
     *  transaction ends so numbers are never shared and, if it rolls back, never skipped. */
    @Transactional
    public int nextClaimNumber(String id) {
        Sheet sheet = sheets.findForUpdate(id).orElseThrow();
        int number = sheet.nextClaimNumber();
        sheets.flush();
        return number;
    }

    /** Hold the sheet locked until the transaction ends, so changes checked against the rest of
     *  the sheet (such as a period that must not overlap another) are made one at a time. */
    @Transactional
    public void lock(String id) {
        sheets.findForUpdate(id).orElseThrow();
    }

    /** Change the sheet's today (the sheet is created first if it was never loaded); it has no
     *  audit line of its own; when it moves, each person's due-total on the day being left is
     *  recorded first (SNAPSHOT), then what follows from it (such as periods closed past their
     *  grace days) and then the figures it moves. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        lock(id);
        RollupService.Snapshot before = rollup.snapshot(id);
        if (!today.equals(sheet.getToday())) {
            rollup.recordSnapshot(id, user, before);
        }
        sheet.setToday(today);
        sheets.flush();
        events.publishEvent(new TodayChanged(id, user, today));
        rollup.recordChanges(id, user, before);
        return sheet;
    }
}
