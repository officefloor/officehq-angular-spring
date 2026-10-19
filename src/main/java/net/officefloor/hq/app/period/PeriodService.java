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

    /** The sheet's periods by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** Add a period; one whose to-date is before its from-date, whose name is taken on the sheet, or that
     * overlaps another of the sheet's periods is refused. */
    @Transactional
    public Period add(String user, String sheetId, String name, LocalDate from, LocalDate to) {
        // Locking the sheet adds its periods one at a time, so two at once cannot overlap.
        sheets.lock(sheetId);
        String trimmed = name.trim();
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts.");
        }
        if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another.");
        }
        Period period;
        try {
            period = periods.saveAndFlush(new Period(sheetId, trimmed, from, to));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + period.getName()
                + " from=" + period.getFrom() + " to=" + period.getTo());
        return period;
    }
}
