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
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The holidays on a sheet: days a claim's due-by, fixed when the claim is added, does not count
 *  among the business days of its terms. */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final ClaimRepository claims;
    private final SheetService sheets;
    private final Audit audit;

    public HolidayService(HolidayRepository holidays, ClaimRepository claims, SheetService sheets, Audit audit) {
        this.holidays = holidays;
        this.claims = claims;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's holidays, the earliest first. */
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add a holiday to the sheet. Refused when the date is already on its list. The sheet is held
     *  locked so the same date added at the same moment is checked against itself. Every claim on the
     *  sheet that is not SETTLED or VOID, is due by the holiday or later and whose due-by a steward did not
     *  set directly has its due-by re-fixed:
     *  worked out again from its date, with the terms it was fixed with, over the holidays now on the
     *  sheet. */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        sheets.lock(sheetId);
        if (holidays.existsBySheetIdAndDate(sheetId, date)) {
            throw new HolidayRefusedException("The date " + date + " is already a holiday on the sheet");
        }
        Holiday saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        Set<LocalDate> days = list(sheetId).stream().map(Holiday::getDate).collect(Collectors.toSet());
        for (Claim claim : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                    || claim.isDueBySet() || claim.getDueBy().isBefore(date)) {
                continue;
            }
            LocalDate dueBy = businessDaysAfter(days, claim.getDate(), claim.getTerms());
            if (!dueBy.equals(claim.getDueBy())) {
                claim.refix(dueBy);
                claims.saveAndFlush(claim);
            }
        }
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + date);
        return saved;
    }

    /** The terms-th business day after a date: counting forward from the day after it, skipping
     *  every Saturday, Sunday and holiday on the sheet now. With no terms, the date itself when it
     *  is a business day, else the next one. */
    public LocalDate businessDaysAfter(String sheetId, LocalDate date, int terms) {
        Set<LocalDate> days = list(sheetId).stream().map(Holiday::getDate).collect(Collectors.toSet());
        return businessDaysAfter(days, date, terms);
    }

    /** The terms-th business day after a date, skipping weekends and the given holidays. */
    public static LocalDate businessDaysAfter(Set<LocalDate> holidays, LocalDate date, int terms) {
        LocalDate day = date;
        if (terms <= 0) {
            while (!isBusinessDay(holidays, day)) {
                day = day.plusDays(1);
            }
            return day;
        }
        for (int counted = 0; counted < terms; ) {
            day = day.plusDays(1);
            if (isBusinessDay(holidays, day)) {
                counted++;
            }
        }
        return day;
    }

    private static boolean isBusinessDay(Set<LocalDate> holidays, LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(day);
    }
}
