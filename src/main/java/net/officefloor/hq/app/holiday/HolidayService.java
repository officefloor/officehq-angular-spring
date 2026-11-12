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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the holidays of a sheet, each date at most once. A due-by fixed when a claim is added counts business days, skipping them; a
 * holiday added later re-fixes the due-by of every claim not SETTLED or VOID that is due by it or later, from the claim's date
 * with the terms it was fixed with, over the holidays then on the sheet.
 */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetRepository sheets;
    private final Audit audit;
    private final ClaimRepository claims;
    private final RollupService rollup;

    public HolidayService(HolidayRepository holidays, SheetRepository sheets, Audit audit, ClaimRepository claims,
            RollupService rollup) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.audit = audit;
        this.claims = claims;
        this.rollup = rollup;
    }

    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add the date to the sheet's holidays; refused when it is already one. */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        if (holidays.existsBySheetIdAndDate(sheetId, date)) {
            throw new HolidayTakenException(sheetId, date);
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Holiday saved;
        try {
            saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        } catch (DataIntegrityViolationException e) {
            throw new HolidayTakenException(sheetId, date);
        }
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + saved.getDate());
        redate(sheetId, date);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Work out again the due-by of every claim not SETTLED or VOID that is due by the new holiday or later; a due-by a
     * steward set directly stays as set.
     */
    private void redate(String sheetId, LocalDate holiday) {
        Set<LocalDate> dates = list(sheetId).stream().map(Holiday::getDate).collect(Collectors.toSet());
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                    || claim.isDueBySet() || claim.getDueBy().isBefore(holiday)) {
                continue;
            }
            LocalDate dueBy = businessDaysAfter(dates, claim.getDate(), claim.getTerms());
            if (!dueBy.equals(claim.getDueBy())) {
                claim.setDueBy(dueBy);
                claims.save(claim);
            }
        }
        claims.flush();
    }

    /**
     * The terms-th business day after the date: counting forward from the day after it, skipping every Saturday,
     * Sunday and holiday on the sheet. Terms of 0: the date itself.
     */
    @Transactional(readOnly = true)
    public LocalDate businessDaysAfter(String sheetId, LocalDate date, int terms) {
        Set<LocalDate> dates = list(sheetId).stream().map(Holiday::getDate).collect(Collectors.toSet());
        return businessDaysAfter(dates, date, terms);
    }

    /** The terms-th day after the date that is neither a Saturday, a Sunday nor one of the holidays. */
    public static LocalDate businessDaysAfter(Set<LocalDate> holidays, LocalDate date, int terms) {
        LocalDate day = date;
        for (int counted = 0; counted < terms;) {
            day = day.plusDays(1);
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(day)) {
                counted++;
            }
        }
        return day;
    }
}
