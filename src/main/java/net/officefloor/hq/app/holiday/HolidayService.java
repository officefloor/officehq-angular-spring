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

/** The holidays on a sheet. A claim's due-by, when it is fixed as the claim is added, is moved past the
 * holidays on the sheet then (see {@link #pastHolidays}); a holiday added later leaves it as it is. */
@Service
public class HolidayService {

    private final SheetRepository sheets;
    private final SheetHolidayRepository holidays;
    private final Audit audit;

    public HolidayService(SheetRepository sheets, SheetHolidayRepository holidays, Audit audit) {
        this.sheets = sheets;
        this.holidays = holidays;
        this.audit = audit;
    }

    /** The sheet's holidays, earliest first. */
    @Transactional(readOnly = true)
    public List<SheetHoliday> holidays(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add a holiday on {@code date}; refused when the date is already on the sheet's list. */
    @Transactional
    public SheetHoliday add(String sheetId, String by, LocalDate date) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (holidays.existsBySheetIdAndDate(sheetId, date)) throw new HolidayTakenException();
        SheetHoliday added = holidays.saveAndFlush(new SheetHoliday(sheetId, date));
        audit.change("HOLIDAY_ADDED", sheetId, by, "date=" + date);
        return added;
    }

    /** {@code date} when it is not a holiday on the sheet, else the next day after it that is not. */
    @Transactional(readOnly = true)
    public LocalDate pastHolidays(String sheetId, LocalDate date) {
        Set<LocalDate> off = holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(SheetHoliday::getDate)
                .collect(Collectors.toSet());
        LocalDate on = date;
        while (off.contains(on)) on = on.plusDays(1);
        return on;
    }
}
