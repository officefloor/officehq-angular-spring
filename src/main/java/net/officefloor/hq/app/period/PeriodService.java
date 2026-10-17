package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The periods on a sheet, in date order. A period's name is unique on its sheet regardless of case,
 * its from-date is not after its to-date, and it overlaps no other period on the sheet.
 */
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

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts");
        }
        sheets.load(sheetId, user);
        // Lock the sheet, so periods added at the same moment are checked against each other.
        sheets.lock(sheetId);
        for (Period other : periods.findBySheetIdOrderByFromAsc(sheetId)) {
            if (other.getName().equalsIgnoreCase(name)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A period named " + name + " exists");
            }
            if (other.overlaps(from, to)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The period overlaps " + other.getName());
            }
        }
        Period period = periods.save(new Period(sheetId, name, from, to));
        audit.change("PERIOD_ADDED", sheetId, user, "period=" + name, "from=" + from, "to=" + to);
        return period;
    }
}
