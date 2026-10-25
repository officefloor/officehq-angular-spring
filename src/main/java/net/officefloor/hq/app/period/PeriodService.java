package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetRows, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    /** The sheet's periods in date order. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** Add a period, refused when its name is in use on the sheet, its to-date is before its
     *  from-date, or it shares a day with another of the sheet's periods. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.load(sheetId);
        // Periods of a sheet are added one at a time, so two added at once cannot overlap.
        sheetRows.findForUpdate(sheetId);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date.");
        }
        if (periods.existsBySheetIdAndNameKey(sheetId, Period.keyOf(name))) {
            throw nameInUse();
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period overlaps another period.");
        }
        Period saved;
        try {
            saved = periods.saveAndFlush(new Period(sheetId, name, from, to));
        } catch (DataIntegrityViolationException raced) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " from=" + saved.getFrom() + " to=" + saved.getTo());
        return saved;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on the sheet.");
    }
}
