package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The holidays of a sheet. */
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

    /** The holidays of the sheet, earliest day first. */
    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDayAsc(sheetId);
    }

    /** Add a day to the sheet's holidays; refused for a day already on the list. */
    @Transactional
    public synchronized Holiday add(String sheetId, String user, LocalDate day) {
        sheets.load(sheetId, user);
        if (holidays.existsBySheetIdAndDay(sheetId, day)) {
            throw alreadyListed();
        }
        Holiday saved;
        try {
            saved = holidays.saveAndFlush(new Holiday(sheetId, day, sheets.today(sheetId)));
        } catch (DataIntegrityViolationException e) {
            throw alreadyListed();
        }
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + day);
        return saved;
    }

    /** The terms-th business day after the day (see {@link BusinessDays#after}), skipping every
     *  Saturday, Sunday and holiday of the sheet. */
    @Transactional(readOnly = true)
    public LocalDate businessDaysAfter(String sheetId, LocalDate day, int terms) {
        Set<LocalDate> days = holidays.findBySheetIdOrderByDayAsc(sheetId).stream().map(Holiday::getDay)
                .collect(Collectors.toSet());
        return BusinessDays.after(day, terms, days);
    }

    private static ResponseStatusException alreadyListed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That day is already a holiday");
    }
}
