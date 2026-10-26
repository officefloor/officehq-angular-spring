package net.officefloor.hq.app.holiday;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's list of holidays. A claim's due-by, when fixed at its creation, counts business days that skip the
 * weekends and the holidays on the sheet then; a holiday added later leaves existing due-by dates as they are.
 */
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

    /** Adds a holiday to the sheet; refused when the date is already on the list. */
    @Transactional
    public Holiday add(String by, String sheetId, LocalDate date) {
        sheets.lock(sheetId);
        if (holidays.existsBySheetIdAndDate(sheetId, date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, date + " is already a holiday.");
        }
        Holiday added = holidays.saveAndFlush(new Holiday(sheetId, date));
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + by + " date=" + date);
        return added;
    }

    /**
     * The terms-th business day after the date: counting forward from the day after it, skipping every Saturday,
     * Sunday and holiday on the sheet. Terms of 0 is the date itself.
     */
    @Transactional(readOnly = true)
    public LocalDate dueBy(String sheetId, LocalDate date, int terms) {
        Set<LocalDate> days = holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
        return businessDaysAfter(date, terms, days);
    }

    /** The terms-th day after the date that is neither a Saturday, a Sunday nor one of the holidays. */
    public static LocalDate businessDaysAfter(LocalDate date, int terms, Set<LocalDate> holidays) {
        LocalDate day = date;
        for (int counted = 0; counted < terms;) {
            day = day.plusDays(1);
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(day)) {
                counted++;
            }
        }
        return day;
    }
}
