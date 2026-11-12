package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's periods: each uniquely named on the sheet, none overlapping another. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final Rollup rollup;
    private final RoleService roles;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, Rollup rollup, RoleService roles,
            Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.rollup = rollup;
        this.roles = roles;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        // Locking the sheet makes two users' periods go one after the other, so neither misses an overlap.
        sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        String n = name.trim();
        if (from.isAfter(to)) {
            throw new PeriodRefusedException("The from-date must be on or before the to-date.");
        }
        List<Period> existing = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        if (existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(n))) {
            throw new PeriodRefusedException("That period name is already in use.");
        }
        existing.stream().filter(p -> p.overlaps(from, to)).findFirst().ifPresent(p -> {
            throw new PeriodRefusedException("The period overlaps " + p.getName() + ".");
        });
        Period period;
        try {
            period = periods.saveAndFlush(new Period(sheetId, n, from, to));
        } catch (DataIntegrityViolationException e) {
            throw new PeriodRefusedException("That period name is already in use.");
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user
                + " period=" + n + " from=" + from + " to=" + to);
        return period;
    }

    /** Generate one period per calendar month from the from-date's month to the to-date's month, each from
     *  the first to the last day of its month and named yyyy-mm; a month whose name is taken or whose days
     *  overlap a period on the sheet is left out. A blank from-date takes the sheet's today. Refused when
     *  the to-date is before the from-date. The periods made, in order. */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate from, LocalDate to) {
        // Locking the sheet makes two users' periods go one after the other, so neither misses an overlap.
        Sheet sheet = sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        LocalDate start = from == null ? sheet.getToday() : from;
        if (to.isBefore(start)) {
            throw new PeriodRefusedException("The to-date must be on or after the from-date.");
        }
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromDateAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            String name = m.toString();
            LocalDate first = m.atDay(1);
            LocalDate last = m.atEndOfMonth();
            if (existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(name) || p.overlaps(first, last))) {
                continue;
            }
            Period period = periods.saveAndFlush(new Period(sheetId, name, first, last));
            existing.add(period);
            made.add(period);
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user
                    + " period=" + name + " from=" + first + " to=" + last);
        }
        return made;
    }

    /** Close a period, fixing each person's due-total for it and its own as it is now; refused when the user's role may not
     *  close it, or it is already closed. A soft-closed period can be closed, fixing its figures afresh. */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        // Locking the sheet makes the close and any claim change on the sheet go one after the other.
        sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        Period period = periods.findByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        roles.require(sheetId, user, "close");
        if (period.isClosed()) {
            throw new PeriodRefusedException("The period is already closed.");
        }
        return closeNow(sheetId, user, period, false);
    }

    /** Soft-close a period, fixing each person's due-total for it and its own as it is now while its claims
     *  can still change; refused when the user's role may not close it, or it is already closed or
     *  soft-closed. */
    @Transactional
    public Period softClose(String sheetId, String user, long periodId) {
        // Locking the sheet makes the soft close and any claim change on the sheet go one after the other.
        sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        Period period = periods.findByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        roles.require(sheetId, user, "close");
        if (period.isClosed() || period.isSoftClosed()) {
            throw new PeriodRefusedException("The period is already closed.");
        }
        period.softClose(rollup.dueBetweenByPerson(sheetId, period.getFromDate(), period.getToDate()));
        periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + user
                + " period=" + period.getName() + " total=" + period.getClosedTotal().toPlainString());
        return period;
    }

    /** Close, in order of their to-dates, every open period whose to-date plus the grace days is before the
     *  sheet's (new) today, each as a close by hand would (a soft-closed period keeps its fixed figures); none
     *  when the grace days are off. The caller holds the sheet's lock. */
    @Transactional
    public void closeOverdue(String sheetId, String user, LocalDate today, Integer graceDays) {
        if (graceDays == null) {
            return;
        }
        periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> !p.isClosed() && !p.isSoftClosed() && p.getToDate().plusDays(graceDays).isBefore(today))
                .sorted(Comparator.comparing(Period::getToDate))
                .forEach(p -> closeNow(sheetId, user, p, true));
    }

    private Period closeNow(String sheetId, String user, Period period, boolean auto) {
        period.close(rollup.dueBetweenByPerson(sheetId, period.getFromDate(), period.getToDate()));
        periods.saveAndFlush(period);
        BigDecimal total = period.getClosedTotal();
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user
                + " period=" + period.getName() + " total=" + total.toPlainString() + (auto ? " auto=yes" : ""));
        return period;
    }

    /** Reopen a closed or soft-closed period, making its due-total live again; by a role the sheet allows (a steward
     *  by the standing rule), and refused when it is open. */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        // Locking the sheet makes the reopen and any claim change on the sheet go one after the other.
        sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        Period period = periods.findByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        roles.require(sheetId, user, "reopen");
        if (!period.isClosed() && !period.isSoftClosed()) {
            throw new PeriodRefusedException("The period is not closed.");
        }
        period.reopen();
        periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + period.getName());
        return period;
    }
}
