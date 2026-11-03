package net.officefloor.hq.app.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The holidays of a sheet. */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetService sheets;
    private final ClaimRepository claims;
    private final Rollup rollup;
    private final Audit audit;

    public HolidayService(HolidayRepository holidays, SheetService sheets, ClaimRepository claims, Rollup rollup,
            Audit audit) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.claims = claims;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The holidays of the sheet, earliest day first. */
    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDayAsc(sheetId);
    }

    /** Add a day to the sheet's holidays; refused for a day already on the list. Every claim of the
     *  sheet that is not settled or void and is due by the day or later, its due-by not set directly by a steward, has its due-by fixed again:
     *  the terms-th business day after its date, by the terms it was fixed with, over the holidays
     *  now on the sheet. */
    @Transactional
    public synchronized Holiday add(String sheetId, String user, LocalDate day) {
        sheets.load(sheetId, user);
        if (holidays.existsBySheetIdAndDay(sheetId, day)) {
            throw alreadyListed();
        }
        return rollup.tracking(sheetId, user, () -> {
            Holiday saved;
            try {
                saved = holidays.saveAndFlush(new Holiday(sheetId, day, sheets.today(sheetId)));
            } catch (DataIntegrityViolationException e) {
                throw alreadyListed();
            }
            audit.recordOnCommit("HOLIDAY_ADDED sheet=" + sheetId + " by=" + user + " date=" + day);
            Set<LocalDate> days = holidayDays(sheetId);
            for (Claim claim : claims.findBySheetIdOrderByListedDesc(sheetId)) {
                if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                        || claim.getTerms() == null || claim.isDueBySet() || claim.getDueBy().isBefore(day)) {
                    continue;
                }
                LocalDate dueBy = BusinessDays.after(claim.getDate(), claim.getTerms(), days);
                if (!dueBy.equals(claim.getDueBy())) {
                    claim.refixDueBy(dueBy);
                    claims.save(claim);
                }
            }
            claims.flush();
            return saved;
        });
    }

    /** The terms-th business day after the day (see {@link BusinessDays#after}), skipping every
     *  Saturday, Sunday and holiday of the sheet. */
    @Transactional(readOnly = true)
    public LocalDate businessDaysAfter(String sheetId, LocalDate day, int terms) {
        return BusinessDays.after(day, terms, holidayDays(sheetId));
    }

    private Set<LocalDate> holidayDays(String sheetId) {
        return holidays.findBySheetIdOrderByDayAsc(sheetId).stream().map(Holiday::getDay)
                .collect(Collectors.toSet());
    }

    private static ResponseStatusException alreadyListed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That day is already a holiday");
    }
}
