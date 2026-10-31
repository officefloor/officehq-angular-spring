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
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The holidays on a sheet. */
@Service
public class HolidayService {

    private final HolidayRepository holidays;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final ClaimRepository claims;
    private final Rollup rollup;
    private final Audit audit;

    public HolidayService(HolidayRepository holidays, SheetService sheets, SheetRepository sheetStore,
            ClaimRepository claims, Rollup rollup, Audit audit) {
        this.holidays = holidays;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.claims = claims;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's holidays, earliest first. */
    @Transactional(readOnly = true)
    public List<Holiday> list(String sheetId) {
        return holidays.findBySheetIdOrderByDateAsc(sheetId);
    }

    /** Add a holiday to the sheet; refused when the date is already on its list. Every claim that is not SETTLED or
     *  VOID and is due by the holiday or later has its due-by fixed again: from its date, on the terms it was fixed
     *  with, over the holidays now on the sheet. */
    @Transactional
    public Holiday add(String sheetId, String user, LocalDate date) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        if (holidays.existsBySheetIdAndDate(sheetId, date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That date is already a holiday");
        }
        Rollup.Figures before = rollup.of(sheetId);
        Holiday saved;
        try {
            saved = holidays.saveAndFlush(new Holiday(sheetId, date));
        } catch (DataIntegrityViolationException raced) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That date is already a holiday");
        }
        audit.recordChangeOnCommit(sheetId, user, "HOLIDAY_ADDED sheet=" + sheetId + " by=" + user
                + " date=" + saved.getDate());
        refixDueBys(sheetId, saved.getDate());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    private void refixDueBys(String sheetId, LocalDate holiday) {
        Set<LocalDate> dates = holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
        for (Claim claim : claims.findBySheetIdOrderByListedDescIdDesc(sheetId)) {
            if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                    || claim.getDueBy().isBefore(holiday)) {
                continue;
            }
            claim.refixDueBy(businessDaysAfter(claim.getDate(), claim.getTerms(), dates));
        }
        claims.flush();
    }

    /** The day a claim dated {@code date} on {@code terms} is due by: the terms-th business day after its date,
     *  counting forward from the day after it and skipping every Saturday, Sunday and holiday on the sheet now;
     *  its date itself on terms of 0. */
    @Transactional(readOnly = true)
    public LocalDate dueBy(String sheetId, LocalDate date, int terms) {
        Set<LocalDate> dates = holidays.findBySheetIdOrderByDateAsc(sheetId).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
        return businessDaysAfter(date, terms, dates);
    }

    /** The {@code terms}-th business day after {@code date}: counting from the day after it, skipping every
     *  Saturday, Sunday and one of {@code holidays}; {@code date} itself on terms of 0. */
    public static LocalDate businessDaysAfter(LocalDate date, int terms, Set<LocalDate> holidays) {
        LocalDate due = date;
        for (int counted = 0; counted < terms; ) {
            due = due.plusDays(1);
            if (isBusinessDay(due, holidays)) {
                counted++;
            }
        }
        return due;
    }

    private static boolean isBusinessDay(LocalDate day, Set<LocalDate> holidays) {
        DayOfWeek weekday = day.getDayOfWeek();
        return weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY && !holidays.contains(day);
    }
}
