package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the periods of each sheet: uniquely named, and no two overlapping. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.audit = audit;
    }

    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAscIdAsc(sheetId);
    }

    /** Add a period; refused when its name is taken, its dates are reversed, or it overlaps another period. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        // Lock the sheet so two periods added at once are checked against each other.
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        if (to.isBefore(from)) {
            throw new PeriodRefusedException("The period " + name + " ends before it starts");
        }
        if (periods.existsBySheetIdAndName(sheetId, name)) {
            throw new PeriodRefusedException("The period name " + name + " is already in use on sheet " + sheetId);
        }
        if (list(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefusedException("The period " + name + " overlaps another period on sheet " + sheetId);
        }
        Period saved;
        try {
            saved = periods.saveAndFlush(new Period(sheetId, name, from, to));
        } catch (DataIntegrityViolationException e) {
            throw new PeriodRefusedException("The period name " + name + " is already in use on sheet " + sheetId);
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        return saved;
    }
}
