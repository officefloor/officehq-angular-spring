package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the periods of each sheet: uniquely named, and no two overlapping; a closed period's claims are fixed. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final RoleService roles;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, Audit audit, RollupService rollup,
            RoleService roles) {
        this.periods = periods;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
        this.roles = roles;
    }

    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAscIdAsc(sheetId);
    }

    /**
     * Add a period, with a cap on the OPEN or PENDING claims each person may have in it (null for none); refused when
     * its name is taken, its dates are reversed, its cap is below 1, or it overlaps another period.
     */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to, Integer cap) {
        // Lock the sheet so two periods added at once are checked against each other.
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        if (to.isBefore(from)) {
            throw new PeriodRefusedException("The period " + name + " ends before it starts");
        }
        if (cap != null && cap < 1) {
            throw new PeriodRefusedException("The period " + name + " cannot cap claims at " + cap);
        }
        if (periods.existsBySheetIdAndName(sheetId, name)) {
            throw new PeriodRefusedException("The period name " + name + " is already in use on sheet " + sheetId);
        }
        if (list(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefusedException("The period " + name + " overlaps another period on sheet " + sheetId);
        }
        Period saved;
        try {
            saved = periods.saveAndFlush(new Period(sheetId, name, from, to, cap));
        } catch (DataIntegrityViolationException e) {
            throw new PeriodRefusedException("The period name " + name + " is already in use on sheet " + sheetId);
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " from=" + saved.getFromDate() + " to=" + saved.getToDate()
                + (saved.getCap() == null ? "" : " cap=" + saved.getCap()));
        return saved;
    }

    /**
     * Generate one period per calendar month from the from-date's month to the to-date's month, each named yyyy-mm and
     * running from the first to the last day of its month; a month whose name is taken or whose days overlap a period
     * on the sheet is left out. Refused when the to-date is before the from-date. The periods made, in date order.
     */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate from, LocalDate to) {
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        if (to.isBefore(from)) {
            throw new PeriodRefusedException("The to-date " + to + " is before the from-date " + from);
        }
        List<Period> existing = new ArrayList<>(list(sheetId));
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (existing.stream().anyMatch(p -> p.getName().equals(name) || p.overlaps(first, last))) {
                continue;
            }
            Period saved = periods.saveAndFlush(new Period(sheetId, name, first, last));
            existing.add(saved);
            made.add(saved);
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                    + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        }
        return made;
    }

    /**
     * Generate the twelve monthly periods of the fiscal year named FY{@code year}, from the first day of the sheet's
     * fiscal year start month in that year, as a generate over its dates would. The periods made, in date order.
     */
    @Transactional
    public List<Period> generateFiscalYear(String sheetId, String user, int year) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(() -> new SheetNotFoundException(sheetId));
        YearMonth first = YearMonth.of(year, sheet.getFiscalStartMonth());
        return generate(sheetId, user, first.atDay(1), first.plusMonths(11).atEndOfMonth());
    }

    /** The name (FYyyyy) of the fiscal year a date falls in on the sheet; by a January start when there is no such sheet. */
    public Function<LocalDate, String> fiscalYears(String sheetId) {
        int startMonth = sheets.findById(sheetId).map(Sheet::getFiscalStartMonth).orElse(1);
        return date -> Sheet.fiscalYearOf(date, startMonth);
    }

    /** Close a period, fixing every person's due-total for it, and so its own, as they are now; refused when it is already closed or the user's role may not close; a soft-closed period's figures are fixed afresh. */
    @Transactional
    public Period close(String sheetId, String user, String name) {
        // Lock the sheet so no claim in the period changes while its total is fixed.
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new PeriodNotFoundException(sheetId, name));
        if (!roles.may(sheetId, user, "close")) {
            throw new PeriodCloseForbiddenException(name, user);
        }
        if (period.isClosed()) {
            throw new PeriodClosedException(name);
        }
        return closeNow(sheetId, user, period, false);
    }

    /**
     * Soft-close a period, fixing every person's due-total for it, and so its own, as they are now, while claims due by
     * a date in it can still change; refused when it is closed or soft-closed already or the user's role may not close.
     */
    @Transactional
    public Period softClose(String sheetId, String user, String name) {
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new PeriodNotFoundException(sheetId, name));
        if (!roles.may(sheetId, user, "close")) {
            throw new PeriodCloseForbiddenException(name, user);
        }
        if (period.isClosed() || period.isSoftClosed()) {
            throw new PeriodClosedException(name);
        }
        period.softClose(rollup.dueTotalsBetween(sheetId, period.getFromDate(), period.getToDate()));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + saved.getClosedTotal().toPlainString());
        return saved;
    }

    /**
     * Close, in order of their to-dates, every open period (neither closed nor soft-closed) whose to-date plus the
     * grace days is before the today, as a close by hand would, each recorded as closed automatically; none when the
     * grace days are off (null).
     */
    @Transactional
    public void autoClose(String sheetId, String user, LocalDate today, Integer graceDays) {
        if (graceDays == null) {
            return;
        }
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        list(sheetId).stream()
                .filter(p -> !p.isClosed() && !p.isSoftClosed() && p.getToDate().plusDays(graceDays).isBefore(today))
                .sorted(Comparator.comparing(Period::getToDate))
                .forEach(p -> closeNow(sheetId, user, p, true));
    }

    private Period closeNow(String sheetId, String user, Period period, boolean auto) {
        period.close(rollup.dueTotalsBetween(sheetId, period.getFromDate(), period.getToDate()));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + saved.getClosedTotal().toPlainString() + (auto ? " auto=yes" : ""));
        return saved;
    }

    /**
     * Reopen a closed or soft-closed period, making its due-total live again; refused unless the user's role may reopen
     * and it is closed or soft-closed.
     */
    @Transactional
    public Period reopen(String sheetId, String user, String name) {
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new PeriodNotFoundException(sheetId, name));
        if (!roles.may(sheetId, user, "reopen")) {
            throw new PeriodReopenForbiddenException(name, user);
        }
        if (!period.isClosed() && !period.isSoftClosed()) {
            throw new PeriodNotClosedException(name);
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + saved.getName());
        return saved;
    }

    /** The name of the period the date falls in; empty when it is in none. */
    public Optional<String> nameOn(String sheetId, LocalDate date) {
        return list(sheetId).stream().filter(p -> p.contains(date)).map(Period::getName).findFirst();
    }

    /** The period the date falls in; empty when it is in none. */
    public Optional<Period> on(String sheetId, LocalDate date) {
        return list(sheetId).stream().filter(p -> p.contains(date)).findFirst();
    }

    /** Whether the date falls in a soft-closed period of the sheet. */
    public boolean softClosedOn(String sheetId, LocalDate date) {
        return list(sheetId).stream().anyMatch(p -> p.isSoftClosed() && p.contains(date));
    }

    /**
     * Refuse a change to a claim due by any of the dates when one falls in a closed period. The sheet is locked
     * until the caller's transaction ends, so a period cannot close while the change is made.
     */
    @Transactional
    public void requireOpen(String sheetId, LocalDate... dates) {
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        for (Period period : list(sheetId)) {
            for (LocalDate date : dates) {
                if (period.isClosed() && period.contains(date)) {
                    throw new PeriodClosedException(period.getName(), date);
                }
            }
        }
    }
}
