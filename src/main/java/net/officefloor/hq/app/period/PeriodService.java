package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sheet's periods: each named uniquely on the sheet, from and to a date inclusive, none overlapping. */
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

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new PeriodDatesException();
        // Lock the sheet so two users adding periods at once cannot overlap or share a name.
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (periods.existsBySheetIdAndName(sheetId, name)) throw new DuplicatePeriodNameException();
        if (periods.findBySheetIdOrderByFromDateAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodOverlapException();
        }
        Period saved = periods.saveAndFlush(new Period(sheetId, name, from, to));
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user.strip()
                + " period=" + saved.getName() + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        return saved;
    }
}
