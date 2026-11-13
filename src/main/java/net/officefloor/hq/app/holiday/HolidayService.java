package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The holidays on a sheet: dates a claim's due-by, fixed when the claim is added, is moved past. */
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

    /** The sheet's holidays, the earliest first. */
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add a holiday to the sheet. Refused when the date is already on its list. The sheet is held
     *  locked so the same date added at the same moment is checked against itself. */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        sheets.lock(sheetId);
        if (holidays.existsBySheetIdAndDate(sheetId, date)) {
            throw new HolidayRefusedException("The date " + date + " is already a holiday on the sheet");
        }
        Holiday saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + date);
        return saved;
    }

    /** The date moved past the sheet's holidays: the date itself when it is not a holiday, else the
     *  next day after it that is not. */
    public LocalDate pastHolidays(String sheetId, LocalDate date) {
        Set<LocalDate> days = list(sheetId).stream().map(Holiday::getDate).collect(Collectors.toSet());
        LocalDate day = date;
        while (days.contains(day)) {
            day = day.plusDays(1);
        }
        return day;
    }
}
