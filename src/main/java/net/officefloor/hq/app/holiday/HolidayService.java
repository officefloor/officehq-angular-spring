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
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's holidays: dates added on the screen, each at most once. A claim's due-by, fixed when
 * it is created, counts business days past them (see {@link #businessDaysAfter}). Adding a holiday
 * works out again the due-by of every claim on the sheet that is not SETTLED or VOID and is due by
 * it or later: from the claim's date, with the terms it was fixed with, over the holidays now on the
 * sheet; figures that move as a result are recorded after HOLIDAY_ADDED.
 */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final RoleService roles;
    private final ClaimRepository claims;
    private final RollupService rollup;

    public HolidayService(HolidayRepository holidays, SheetService sheets, SheetRepository sheetRows, Audit audit,
            RoleService roles, ClaimRepository claims, RollupService rollup) {
        this.claims = claims;
        this.rollup = rollup;
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
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Holiday added;
        try {
            added = holidays.saveAndFlush(new Holiday(sheetId, day));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That date is already a holiday.");
        }
        Set<LocalDate> off = offDays(sheetId);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (claim.getStatus() != ClaimStatus.SETTLED && claim.getStatus() != ClaimStatus.VOID
                    && !claim.getDueBy().isBefore(day)) {
                claim.setDueBy(businessDaysAfter(off, claim.getDate(), claim.getTerms()));
            }
        }
        claims.flush();
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + added.getDay());
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    /**
     * The {@code days}-th business day after the date: counting forward from the day after it,
     * skipping every Saturday, Sunday and holiday on the sheet; the date itself when {@code days} is 0.
     */
    @Transactional(readOnly = true)
    public LocalDate businessDaysAfter(String sheetId, LocalDate date, int days) {
        return businessDaysAfter(offDays(sheetId), date, days);
    }

    private Set<LocalDate> offDays(String sheetId) {
        return holidays.findBySheetIdOrderByDayAsc(sheetId).stream().map(Holiday::getDay)
                .collect(Collectors.toSet());
    }

    /** The {@code days}-th day after the date that is no Saturday, Sunday or one of the holidays. */
    public static LocalDate businessDaysAfter(Set<LocalDate> holidays, LocalDate date, int days) {
        LocalDate d = date;
        for (int left = days; left > 0;) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(d)) {
                left--;
            }
        }
        return d;
    }
}
