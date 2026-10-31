package net.officefloor.hq.app.holiday;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The holidays on a sheet, earliest first; no date is on it twice. A claim's due-by, when it is fixed as the
 * claim is added, is the terms-th business day after its date, skipping weekends and the holidays on the sheet
 * then (see {@link #dueBy}). A holiday added later fixes again the due-by of every claim on the sheet that is
 * not SETTLED or VOID and is due on or after it, from the claim's date with the terms it was fixed with; a
 * due-by a steward set directly stays as set.
 */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ClaimRepository claims;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public HolidayService(HolidayRepository holidays, SheetService sheets, SheetRepository sheetRows,
            ClaimRepository claims, Rollup rollup, ApplicationEventPublisher events) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.claims = claims;
        this.rollup = rollup;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDayAsc(sheetId);
    }

    /**
     * Add a holiday, fixing again the due-by of each claim not SETTLED or VOID due on or after it; refused when
     * the date is already on the sheet's list.
     */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (holidays.existsBySheetIdAndDay(sheetId, date)) {
            throw new HolidayRefused("That date is already a holiday on this sheet.");
        }
        Rollup.Figures before = rollup.figures(sheetId);
        Holiday saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        Set<LocalDate> days = list(sheetId).stream().map(Holiday::getDay).collect(Collectors.toSet());
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (claim.getStatus() != ClaimStatus.SETTLED && claim.getStatus() != ClaimStatus.VOID
                    && !claim.isDueBySet() && !claim.getDueBy().isBefore(date)) {
                claim.setDueBy(businessDaysAfter(claim.getDate(), claim.getTerms(), days));
            }
        }
        claims.flush();
        events.publishEvent(new HolidayAdded(sheetId, user, date));
        rollup.changed(sheetId, user, before);
        return saved;
    }

    /** The due-by of a claim dated {@code date} with these terms, see {@link #businessDaysAfter}. */
    @Transactional(readOnly = true)
    public LocalDate dueBy(String sheetId, LocalDate date, int terms) {
        Set<LocalDate> days = list(sheetId).stream().map(Holiday::getDay).collect(Collectors.toSet());
        return businessDaysAfter(date, terms, days);
    }

    /**
     * The {@code terms}-th business day after the date, counting forward from the day after it and skipping
     * every Saturday, Sunday and holiday given; the date itself when the terms are 0 or less.
     */
    public static LocalDate businessDaysAfter(LocalDate date, int terms, Set<LocalDate> holidays) {
        LocalDate day = date;
        for (int left = terms; left > 0; ) {
            day = day.plusDays(1);
            if (businessDay(day, holidays)) {
                left--;
            }
        }
        return day;
    }

    private static boolean businessDay(LocalDate day, Set<LocalDate> holidays) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(day);
    }
}
