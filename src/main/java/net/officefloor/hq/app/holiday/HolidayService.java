package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The holidays on a sheet, earliest first; no date is on it twice. A claim's due-by, when it is fixed as the
 * claim is added, is moved past the holidays on the sheet then (see {@link #pastHolidays}); a holiday added
 * later leaves the due-by dates already fixed as they are.
 */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;

    public HolidayService(HolidayRepository holidays, SheetService sheets, SheetRepository sheetRows,
            ApplicationEventPublisher events) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDayAsc(sheetId);
    }

    /** Add a holiday; refused when the date is already on the sheet's list. */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (holidays.existsBySheetIdAndDay(sheetId, date)) {
            throw new HolidayRefused("That date is already a holiday on this sheet.");
        }
        Holiday saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        events.publishEvent(new HolidayAdded(sheetId, user, date));
        return saved;
    }

    /** The date, or when it is a holiday on the sheet, the next day after it that is not. */
    @Transactional(readOnly = true)
    public LocalDate pastHolidays(String sheetId, LocalDate date) {
        Set<LocalDate> days = list(sheetId).stream().map(Holiday::getDay).collect(Collectors.toSet());
        LocalDate day = date;
        while (days.contains(day)) {
            day = day.plusDays(1);
        }
        return day;
    }
}
