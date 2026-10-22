package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.BusinessDays;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The holidays on a sheet: listed, added, and the days a due-by does not count as business days. */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetService sheets;
    private final ClaimRepository claims;
    private final Rollups rollups;
    private final Audit audit;

    public HolidayService(HolidayRepository holidays, SheetService sheets, ClaimRepository claims, Rollups rollups,
            Audit audit) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.claims = claims;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's holidays by date. */
    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /**
     * Add a holiday to the sheet; refused when the date is already on its list. Every claim's due-by on
     * the sheet is worked out again against the holidays now, and the figures that moves are audited.
     */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        // Take the sheet's lock first, so the same date added twice at once cannot both pass the check.
        sheets.lock(sheetId);
        if (holidays.existsBySheetIdAndDate(sheetId, date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That date is already a holiday on this sheet.");
        }
        Rollups.Rollup before = rollups.take(sheetId);
        Holiday saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        Set<LocalDate> days = days(sheetId);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            claim.workOutDueBy(days);
        }
        claims.flush();
        audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + date);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /** The terms-th business day after a date, skipping the holidays on the sheet now (see {@link BusinessDays#dueBy}). */
    @Transactional(readOnly = true)
    public LocalDate dueBy(String sheetId, LocalDate date, int terms) {
        return BusinessDays.dueBy(date, terms, days(sheetId));
    }

    private Set<LocalDate> days(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
    }
}
