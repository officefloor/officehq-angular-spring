package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The periods on a sheet, earliest first: none share a name (case ignored) or a date. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetRows,
            ApplicationEventPublisher events) {
        this.periods = periods;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /**
     * Add a period from {@code from} to {@code to}, both inclusive. Refused when the name is taken on the
     * sheet, the to-date is before the from-date, or it shares a date with another period. Two users adding
     * one at the same moment wait on each other, so no two overlap.
     */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (to.isBefore(from)) {
            throw new PeriodRefused("The to-date is before the from-date.");
        }
        if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name)) {
            throw new PeriodRefused("That period name is already in use on this sheet.");
        }
        if (list(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefused("The period overlaps another period.");
        }
        Period period = periods.saveAndFlush(new Period(sheetId, name, from, to));
        events.publishEvent(new PeriodAdded(sheetId, user, name, from, to));
        return period;
    }
}
