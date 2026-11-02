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

/**
 * A sheet's list of holidays, each date at most once. A claim's due-by, fixed when it is added, counts
 * its terms in business days, skipping weekends and these holidays. Adding a holiday fixes again, with the
 * same terms, the due-by of every claim not SETTLED or VOID whose due-by is on or after it, except one whose
 * due-by a steward set directly.
 */
@Service
public class HolidayService {

    private final SheetRepository sheets;
    private final HolidayRepository holidays;
    private final ClaimRepository claims;
    private final RollupService rollup;
    private final Audit audit;

    public HolidayService(SheetRepository sheets, HolidayRepository holidays, ClaimRepository claims,
            RollupService rollup, Audit audit) {
        this.sheets = sheets;
        this.holidays = holidays;
        this.claims = claims;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's holidays, earliest first. */
    @Transactional(readOnly = true)
    public List<Holiday> holidays(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /**
     * Add the date to the sheet's holidays; refused for a date already on the list. Every claim not SETTLED
     * or VOID whose due-by is on or after it has its due-by worked out again from its date, with its terms,
     * over the holidays now; the figures it moves are audited after HOLIDAY_ADDED.
     */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (holidays.existsBySheetIdAndDate(sheetId, date)) throw new DuplicateHolidayException();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Holiday saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        Set<LocalDate> days = holidays.findBySheetIdOrderByDateAsc(sheetId).stream()
                .map(Holiday::getDate).collect(Collectors.toSet());
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) continue;
            if (claim.isDueBySet() || claim.getDueBy().isBefore(date)) continue;
            LocalDate dueBy = businessDaysAfter(claim.getDate(), claim.getTerms(), days);
            if (!dueBy.equals(claim.getDueBy())) claim.setDueBy(dueBy);
        }
        claims.flush();
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user.strip() + " date=" + date);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * The terms-th business day after the date: counting forward from the day after it, skipping every
     * Saturday, Sunday and holiday on the sheet; the date itself for terms of zero.
     */
    @Transactional(readOnly = true)
    public LocalDate businessDaysAfter(String sheetId, LocalDate date, int terms) {
        Set<LocalDate> days = holidays.findBySheetIdOrderByDateAsc(sheetId).stream()
                .map(Holiday::getDate).collect(Collectors.toSet());
        return businessDaysAfter(date, terms, days);
    }

    /** The terms-th day after the date that is neither a Saturday, a Sunday nor one of the holidays. */
    public static LocalDate businessDaysAfter(LocalDate date, int terms, Set<LocalDate> holidays) {
        LocalDate d = date;
        for (int left = terms; left > 0; ) {
            d = d.plusDays(1);
            if (isBusinessDay(d, holidays)) left--;
        }
        return d;
    }

    private static boolean isBusinessDay(LocalDate d, Set<LocalDate> holidays) {
        DayOfWeek day = d.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(d);
    }
}
