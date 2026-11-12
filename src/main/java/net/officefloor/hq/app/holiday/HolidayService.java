package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's list of holidays, and moves a due-by date past them. */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetService sheets;
    private final Audit audit;

    public HolidayService(HolidayRepository holidays, SheetService sheets, Audit audit) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's holidays, earliest first. */
    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add a date to the sheet's holidays; a date already on the list is refused. */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        sheets.today(sheetId);
        if (holidays.existsBySheetIdAndDate(sheetId, date)) {
            throw new HolidayRefusedException(date + " is already a holiday.");
        }
        Holiday saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + date);
        return saved;
    }

    /** The date, or when it is one of the sheet's holidays the next day that is not. */
    @Transactional(readOnly = true)
    public LocalDate pastHolidays(String sheetId, LocalDate date) {
        Set<LocalDate> days = holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
        LocalDate d = date;
        while (days.contains(d)) {
            d = d.plusDays(1);
        }
        return d;
    }
}
