package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
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
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** Add a period to the sheet (the sheet is created first if it was never loaded; a from-date left out is the sheet's today); refused when
     *  its from-date is after its to-date, its name is in use on the sheet or it overlaps another. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate fromOrNull, LocalDate to) {
        sheets.load(sheetId, user);
        LocalDate from = fromOrNull != null ? fromOrNull : sheets.today(sheetId);
        // held until the transaction ends, so two periods added at once are checked one after the other
        sheets.lock(sheetId);
        String trimmed = name.trim();
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The from-date is after the to-date");
        }
        List<Period> existing = periods.findBySheetIdOrderByFromAsc(sheetId);
        if (existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(trimmed))) {
            throw nameInUse();
        }
        if (existing.stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period overlaps another");
        }
        Period period;
        try {
            period = periods.saveAndFlush(new Period(sheetId, trimmed, from, to));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user
                + " period=" + trimmed + " from=" + from + " to=" + to);
        return period;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The period name is in use on the sheet");
    }
}
