package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.rollup.RollupService.Snapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Changing a sheet's today, and what follows from it. */
@Service
public class TodayService {

    private final SheetRepository sheets;
    private final SheetService sheetService;
    private final PeriodService periods;
    private final RollupService rollup;

    public TodayService(SheetRepository sheets, SheetService sheetService, PeriodService periods,
            RollupService rollup) {
        this.sheets = sheets;
        this.sheetService = sheetService;
        this.periods = periods;
        this.rollup = rollup;
    }

    /** Change the sheet's today; it writes no line of its own. Every open period past the sheet's grace days is
     * then closed, each writing its line, and then the figures the change moves are written. */
    @Transactional
    public Sheet setToday(String user, String id, LocalDate today) {
        sheetService.lock(id);
        Sheet sheet = sheets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        Snapshot before = rollup.snapshot(id);
        sheet.setToday(today);
        sheets.flush();
        periods.closeLapsed(user, id);
        rollup.recordChanges(user, id, before);
        return sheet;
    }
}
