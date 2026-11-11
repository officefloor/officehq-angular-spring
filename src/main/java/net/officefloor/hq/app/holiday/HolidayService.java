package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the holidays of a sheet, each date at most once. A due-by fixed when a claim is added is moved past them; a
 * holiday added later leaves the due-by dates already fixed as they are.
 */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetRepository sheets;
    private final Audit audit;

    public HolidayService(HolidayRepository holidays, SheetRepository sheets, Audit audit) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.audit = audit;
    }

    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add the date to the sheet's holidays; refused when it is already one. */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        if (holidays.existsBySheetIdAndDate(sheetId, date)) {
            throw new HolidayTakenException(sheetId, date);
        }
        Holiday saved;
        try {
            saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        } catch (DataIntegrityViolationException e) {
            throw new HolidayTakenException(sheetId, date);
        }
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + saved.getDate());
        return saved;
    }

    /** The date itself when it is not a holiday on the sheet, else the next day that is not one. */
    @Transactional(readOnly = true)
    public LocalDate nextNonHoliday(String sheetId, LocalDate date) {
        Set<LocalDate> dates = list(sheetId).stream().map(Holiday::getDate).collect(Collectors.toSet());
        LocalDate day = date;
        while (dates.contains(day)) {
            day = day.plusDays(1);
        }
        return day;
    }
}
