package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;

    public HolidayService(HolidayRepository holidays, SheetService sheets, SheetRepository sheetRows, Audit audit) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    /** The sheet's holidays in date order. */
    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add a holiday to the sheet, refused when the date is already one. */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        if (holidays.existsBySheetIdAndDate(sheetId, date)) throw alreadyHoliday(date);
        Holiday saved;
        try {
            saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        } catch (DataIntegrityViolationException raced) {
            throw alreadyHoliday(date);
        }
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + saved.getDate());
        return saved;
    }

    /** The due-by of a claim whose date plus its terms is the date given: that date, else, when it is a
     *  holiday of the sheet now, the next day that is not one. */
    @Transactional(readOnly = true)
    public LocalDate dueBy(String sheetId, LocalDate date) {
        Set<LocalDate> days = holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
        LocalDate due = date;
        while (days.contains(due)) due = due.plusDays(1);
        return due;
    }

    private static ResponseStatusException alreadyHoliday(LocalDate date) {
        return new ResponseStatusException(HttpStatus.CONFLICT, date + " is already a holiday.");
    }
}
