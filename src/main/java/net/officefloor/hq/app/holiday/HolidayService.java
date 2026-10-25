package net.officefloor.hq.app.holiday;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The holidays on a sheet. A claim's due-by is the terms-th business day after the date it counts from,
 * skipping every Saturday, Sunday and holiday on the sheet (see {@link #dueBy}); a holiday added later
 * re-fixes the due-by of every claim not SETTLED or VOID whose due-by is on or after it (see {@link #add}). */
@Service
public class HolidayService {

    private final SheetRepository sheets;
    private final SheetHolidayRepository holidays;
    private final ClaimRepository claims;
    private final RollupService rollup;
    private final Audit audit;

    public HolidayService(SheetRepository sheets, SheetHolidayRepository holidays, ClaimRepository claims,
            RollupService rollup, Audit audit) {
        this.sheets = sheets;
        this.holidays = holidays;
        this.claims = claims;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's holidays, earliest first. */
    @Transactional(readOnly = true)
    public List<SheetHoliday> holidays(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add a holiday on {@code date}; refused when the date is already on the sheet's list. Every claim on
     * the sheet that is not SETTLED or VOID and whose due-by is on or after {@code date} has its due-by
     * worked out again from its date, with the terms it was fixed with, over the sheet's holidays now;
     * a due-by a steward set directly stays as set. */
    @Transactional
    public SheetHoliday add(String sheetId, String by, LocalDate date) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (holidays.existsBySheetIdAndDate(sheetId, date)) throw new HolidayTakenException();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        SheetHoliday added = holidays.saveAndFlush(new SheetHoliday(sheetId, date));
        redate(sheetId, date);
        audit.change("HOLIDAY_ADDED", sheetId, by, "date=" + date);
        rollup.recordSince(sheetId, by, before);
        return added;
    }

    /** The due-by of a claim counting from {@code from} with {@code terms}: the terms-th business day
     * after it, counting forward from the day after and skipping every Saturday, Sunday and holiday on
     * the sheet; with terms of 0, {@code from} itself. */
    @Transactional(readOnly = true)
    public LocalDate dueBy(String sheetId, LocalDate from, int terms) {
        return dueBy(offOn(sheetId), from, terms);
    }

    private void redate(String sheetId, LocalDate holiday) {
        Set<LocalDate> off = offOn(sheetId);
        for (Claim claim : claims.findBySheetIdOrderByListedDescIdDesc(sheetId)) {
            if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) continue;
            if (claim.isDueBySet()) continue;
            if (claim.getDueBy() == null || claim.getDueBy().isBefore(holiday)) continue;
            LocalDate dueBy = dueBy(off, claim.getDate(), claim.getTerms());
            if (!dueBy.equals(claim.getDueBy())) claim.setDueBy(dueBy);
        }
        claims.flush();
    }

    private Set<LocalDate> offOn(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(SheetHoliday::getDate)
                .collect(Collectors.toSet());
    }

    private static LocalDate dueBy(Set<LocalDate> off, LocalDate from, int terms) {
        LocalDate on = from;
        for (int counted = 0; counted < terms;) {
            on = on.plusDays(1);
            if (isBusinessDay(off, on)) counted++;
        }
        return on;
    }

    private static boolean isBusinessDay(Set<LocalDate> off, LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !off.contains(date);
    }
}
