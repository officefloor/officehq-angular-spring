package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;

    public HolidayService(HolidayRepository holidays, SheetService sheets, SheetRepository sheetRows, Audit audit,
            ApplicationEventPublisher events) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
        this.events = events;
    }

    /** The sheet's holidays in date order. */
    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add a holiday to the sheet, refused when the date is already one. Every due-by on the sheet is
     *  then worked out again, past the holidays now. */
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
        events.publishEvent(new HolidaysChanged(sheetId, user));
        return saved;
    }

    /** The due-by of a claim fixed from the date with the terms: the terms-th business day after the
     *  date, skipping every Saturday, Sunday and holiday of the sheet now. */
    @Transactional(readOnly = true)
    public LocalDate dueBy(String sheetId, LocalDate date, int terms) {
        return BusinessDays.after(date, terms, dates(sheetId));
    }

    /** The dates of the sheet's holidays now. */
    @Transactional(readOnly = true)
    public Set<LocalDate> dates(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
    }

    private static ResponseStatusException alreadyHoliday(LocalDate date) {
        return new ResponseStatusException(HttpStatus.CONFLICT, date + " is already a holiday.");
    }
}
