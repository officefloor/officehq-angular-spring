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

/** The holidays on a sheet. */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public HolidayService(HolidayRepository holidays, SheetService sheets, SheetRepository sheetStore, Audit audit) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** The sheet's holidays, earliest first. */
    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add a holiday to the sheet; refused when the date is already on its list. Claims already made keep their
     *  due-by. */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        if (holidays.existsBySheetIdAndDate(sheetId, date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That date is already a holiday");
        }
        Holiday saved;
        try {
            saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That date is already a holiday");
        }
        audit.recordChangeOnCommit(sheetId, user, "HOLIDAY_ADDED sheet=" + sheetId + " by=" + user
                + " date=" + saved.getDate());
        return saved;
    }

    /** The day a claim is due by when its date plus its terms is {@code day}: the first day from it on that is not
     *  one of the sheet's holidays now. */
    @Transactional(readOnly = true)
    public LocalDate pastHolidays(String sheetId, LocalDate day) {
        Set<LocalDate> dates = holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
        LocalDate due = day;
        while (dates.contains(due)) {
            due = due.plusDays(1);
        }
        return due;
    }
}
