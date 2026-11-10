package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's holidays: dates added on the screen, each at most once. A claim's due-by, fixed when
 * it is created, is moved past them (see {@link #pastHolidays}); a holiday added later moves no
 * claim's due-by.
 */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final RoleService roles;

    public HolidayService(HolidayRepository holidays, SheetService sheets, SheetRepository sheetRows, Audit audit,
            RoleService roles) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDayAsc(sheetId);
    }

    /** Add a holiday; a date already on the sheet's list is refused (409). */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate day) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        // hold the sheet so the same date added twice at the same moment is checked one after the other
        sheetRows.findLockedById(sheetId);
        if (holidays.existsBySheetIdAndDay(sheetId, day)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That date is already a holiday.");
        }
        Holiday added;
        try {
            added = holidays.saveAndFlush(new Holiday(sheetId, day));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That date is already a holiday.");
        }
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + added.getDay());
        return added;
    }

    /** The date itself when it is no holiday on the sheet, else the next day that is not. */
    @Transactional(readOnly = true)
    public LocalDate pastHolidays(String sheetId, LocalDate date) {
        Set<LocalDate> days = holidays.findBySheetIdOrderByDayAsc(sheetId).stream().map(Holiday::getDay)
                .collect(Collectors.toSet());
        LocalDate d = date;
        while (days.contains(d)) {
            d = d.plusDays(1);
        }
        return d;
    }
}
