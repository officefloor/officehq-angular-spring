package net.officefloor.hq.app.holiday;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's list of holidays, and counts business days past them. */
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

    /** The terms-th business day after the date: counting forward from the day after it, skipping every
     *  Saturday, Sunday and holiday on the sheet now. Terms of 0 leave the date as it is. */
    @Transactional(readOnly = true)
    public LocalDate businessDaysAfter(String sheetId, LocalDate date, int terms) {
        Set<LocalDate> days = holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
        return businessDaysAfter(date, terms, days);
    }

    /** The terms-th business day after the date, skipping weekends and the given holidays. */
    public static LocalDate businessDaysAfter(LocalDate date, int terms, Set<LocalDate> holidays) {
        LocalDate d = date;
        int counted = 0;
        while (counted < terms) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(d)) {
                counted++;
            }
        }
        return d;
    }
}
