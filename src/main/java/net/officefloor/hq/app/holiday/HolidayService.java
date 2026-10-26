package net.officefloor.hq.app.holiday;

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
 * The sheet's list of holidays. A claim's due-by, when fixed at its creation, is moved past the holidays on the sheet
 * then; a holiday added later leaves existing due-by dates as they are.
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

    /** The date, or when it falls on one of the sheet's holidays, the next day that is not a holiday. */
    @Transactional(readOnly = true)
    public LocalDate pastHolidays(String sheetId, LocalDate date) {
        Set<LocalDate> days = holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
        while (days.contains(date)) {
            date = date.plusDays(1);
        }
        return date;
    }
}
