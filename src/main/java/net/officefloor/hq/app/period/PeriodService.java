package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's periods: added on the screen, each a name unique on the sheet (without regard to
 * case) and a from and to date, both inclusive; no two periods on a sheet overlap. A period can be
 * closed once: closing fixes its due-total at that moment, and no claim whose due-by is inside a
 * closed period can be added, edited, opened, settled or voided. A steward can reopen a closed period, which
 * makes its due-total live again. A period can instead be soft-closed: its figures are fixed in the
 * same way but its claims can still change; it can then be closed (fixing them afresh) or reopened. With grace days set, a change of the sheet's today closes the
 * periods past them (see {@link SheetService#setToday}).
 */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final RoleService roles;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetRows, Audit audit,
            RoleService roles) {
        this.roles = roles;
        this.periods = periods;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to, Integer cap) {
        roles.refuseViewer(sheetId, user);
        if (cap != null && cap < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period's cap must be a whole number above 0.");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period's to-date cannot be before its from-date.");
        }
        sheets.load(sheetId, user);
        // hold the sheet so two periods added at the same moment are checked one after the other
        sheetRows.findLockedById(sheetId);
        if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet.");
        }
        if (periods.existsBySheetIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, to, from)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another.");
        }
        Period added;
        try {
            added = periods.saveAndFlush(new Period(sheetId, name, from, to, cap));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet.");
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + added.getName() + " from="
                + added.getFromDate() + " to=" + added.getToDate()
                + (added.getClaimCap() != null ? " cap=" + added.getClaimCap() : ""));
        return added;
    }

    /**
     * Generate one period per calendar month from the from-date's month to the to-date's month, each
     * running from the first to the last day of its month and named yyyy-mm. A month whose name is
     * already a period, or whose days overlap a period on the sheet, is left out; the others are
     * still made. Refused (400) when the to-date is before the from-date.
     */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate from, LocalDate to) {
        roles.refuseViewer(sheetId, user);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date cannot be before the from-date.");
        }
        sheets.load(sheetId, user);
        // hold the sheet so periods added at the same moment are checked one after the other
        sheetRows.findLockedById(sheetId);
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name)
                    || periods.existsBySheetIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, last, first)) {
                continue;
            }
            Period added = periods.saveAndFlush(new Period(sheetId, name, first, last));
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + added.getName()
                    + " from=" + added.getFromDate() + " to=" + added.getToDate());
            made.add(added);
        }
        return made;
    }

    /**
     * Generate the twelve monthly periods of the fiscal year named FY&lt;year&gt;: from the first day
     * of the sheet's fiscal start month in that year, exactly as {@link #generate} does.
     */
    @Transactional
    public List<Period> generateFiscalYear(String sheetId, String user, int year) {
        roles.refuseViewer(sheetId, user);
        LocalDate first = LocalDate.of(year, sheets.load(sheetId, user).getFiscalStartMonth(), 1);
        return generate(sheetId, user, first, first.plusMonths(11));
    }

    /** The fiscal year each of the sheet's periods falls in (by its from-date), by period id. */
    @Transactional(readOnly = true)
    public Map<Long, String> fiscalYears(String sheetId, List<Period> list) {
        int startMonth = sheetRows.findById(sheetId).map(Sheet::getFiscalStartMonth).orElse(1);
        Map<Long, String> years = new HashMap<>();
        list.forEach(p -> years.put(p.getId(), Sheet.fiscalYearOf(p.getFromDate(), startMonth)));
        return years;
    }

    /** Close a period, fixing its due-total now; refused (403) for a role the permission matrix does not allow and (409) when it is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long id) {
        roles.refuseUnlessPermitted(sheetId, user, "close");
        // hold the sheet so no claim changes while the period's due-total is fixed
        sheetRows.findLockedById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed.");
        }
        return sheets.closePeriod(sheetId, user, period, false);
    }

    /**
     * Soft-close a period, fixing its due-total and each person's now while claims dated inside it
     * can still change; refused (403) for a role the permission matrix does not allow to close and
     * (409) when it is already closed or soft-closed.
     */
    @Transactional
    public Period softClose(String sheetId, String user, long id) {
        roles.refuseUnlessPermitted(sheetId, user, "close");
        // hold the sheet so no claim changes while the period's due-total is fixed
        sheetRows.findLockedById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        if (period.isClosed() || period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed.");
        }
        return sheets.softClosePeriod(sheetId, user, period);
    }

    /**
     * Reopen a closed or soft-closed period, making its due-total live again; refused (403) for a role the
     * permission matrix does not allow and (409) when the period is not closed.
     */
    @Transactional
    public Period reopen(String sheetId, String user, long id) {
        sheetRows.findLockedById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        roles.refuseUnlessPermitted(sheetId, user, "reopen");
        if (!period.isClosed() && !period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is not closed.");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + saved.getName());
        return saved;
    }

    /**
     * Refuse (423) a change to a claim due (by its due-by) inside a closed period. Holds the sheet first, so a
     * period cannot close while the change is under way.
     */
    public void refuseIfClosed(String sheetId, LocalDate date) {
        if (isClosed(sheetId, date)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "That claim's date is inside a closed period.");
        }
    }

    /**
     * Whether a claim due by this day lies inside a closed period. Holds the sheet first, so a
     * period cannot close while a change that relies on the answer is under way.
     */
    public boolean isClosed(String sheetId, LocalDate date) {
        sheetRows.findLockedById(sheetId);
        return periods.existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, date,
                date);
    }

    /** The period on the sheet a claim due by this day belongs to, if any. */
    public Optional<Period> on(String sheetId, LocalDate date) {
        return periods.findBySheetIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, date, date);
    }

    /** Whether a claim due by this day lies inside a soft-closed period. */
    public boolean isSoftClosed(String sheetId, LocalDate date) {
        return periods.existsBySheetIdAndSoftClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId,
                date, date);
    }
}
