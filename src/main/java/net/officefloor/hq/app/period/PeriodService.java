package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The periods on a sheet: named date ranges, no two overlapping. */
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

    /** The sheet's periods, the earliest first. */
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** Add a period from and to a date, inclusive. Refused when another period on the sheet has the
     *  name, the from-date is after the to-date, or it shares a day with another period. The sheet
     *  is held locked so periods added at the same moment are checked against each other. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.lock(sheetId);
        if (from.isAfter(to)) {
            throw new PeriodRefusedException("The period's from-date is after its to-date");
        }
        if (periods.nameInUse(sheetId, name)) {
            throw new PeriodRefusedException("The name " + name + " is already in use on the sheet");
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefusedException("The period overlaps another on the sheet");
        }
        Period saved = periods.saveAndFlush(new Period(sheetId, name, from, to));
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + name
                + " from=" + from + " to=" + to);
        return saved;
    }
}
