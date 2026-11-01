package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's list of holidays, each date at most once. A claim's due-by, fixed when it is added, is
 * moved past them: the next day that is not a holiday. A holiday added later moves no due-by.
 */
@Service
public class HolidayService {

    private final SheetRepository sheets;
    private final HolidayRepository holidays;
    private final Audit audit;

    public HolidayService(SheetRepository sheets, HolidayRepository holidays, Audit audit) {
        this.sheets = sheets;
        this.holidays = holidays;
        this.audit = audit;
    }

    /** The sheet's holidays, earliest first. */
    @Transactional(readOnly = true)
    public List<Holiday> holidays(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add the date to the sheet's holidays; refused for a date already on the list. */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (holidays.existsBySheetIdAndDate(sheetId, date)) throw new DuplicateHolidayException();
        Holiday saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user.strip() + " date=" + date);
        return saved;
    }

    /** The date, or when it is a holiday on the sheet, the next day that is not. */
    @Transactional(readOnly = true)
    public LocalDate pastHolidays(String sheetId, LocalDate date) {
        Set<LocalDate> days = holidays.findBySheetIdOrderByDateAsc(sheetId).stream()
                .map(Holiday::getDate).collect(Collectors.toSet());
        LocalDate d = date;
        while (days.contains(d)) d = d.plusDays(1);
        return d;
    }
}
