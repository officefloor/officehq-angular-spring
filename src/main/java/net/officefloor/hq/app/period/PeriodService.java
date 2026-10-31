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

/** The periods of a sheet. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetStore, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** The sheet's periods, by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /** Add a period; the name is stored trimmed. Refused when the name is in use on the sheet, the
     *  to-date is before the from-date, or it shares a day with another period of the sheet. With no
     *  from-date it starts on the sheet's today. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate fromDate, LocalDate to) {
        sheets.load(sheetId, user);
        // The sheet is locked so two periods added at the same moment cannot overlap.
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        LocalDate from = fromDate != null ? fromDate : today;
        String trimmed = name.trim();
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts");
        }
        List<Period> existing = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        if (existing.stream().anyMatch(p -> p.getName().equals(trimmed))) {
            throw nameInUse();
        }
        if (existing.stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another on this sheet");
        }
        Period added;
        try {
            added = periods.saveAndFlush(new Period(sheetId, trimmed, from, to));
        } catch (DataIntegrityViolationException raced) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + added.getName()
                + " from=" + added.getFromDate() + " to=" + added.getToDate());
        return added;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet");
    }
}
