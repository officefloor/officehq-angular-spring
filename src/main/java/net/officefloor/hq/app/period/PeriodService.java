package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The periods of each sheet: uniquely named, none overlapping another. */
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

    /** The sheet's periods, by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        if (to.isBefore(from)) {
            throw new PeriodRefusedException("to before from");
        }
        if (periods.findBySheetIdAndNameKey(sheetId, Period.keyOf(name)).isPresent()) {
            throw new PeriodRefusedException("name taken");
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefusedException("overlaps");
        }
        Period period = periods.saveAndFlush(new Period(sheetId, name, from, to));
        audit.change("PERIOD_ADDED", sheetId, user, "period=" + period.getName(), "from=" + period.getFrom(),
                "to=" + period.getTo());
        return period;
    }
}
