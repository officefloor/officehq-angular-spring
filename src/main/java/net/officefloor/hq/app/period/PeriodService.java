package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The periods of a sheet. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;
    private final Rollup rollup;
    private final RoleService roles;
    private final PersonRepository people;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetStore, Audit audit,
            Rollup rollup, RoleService roles, PersonRepository people) {
        this.roles = roles;
        this.people = people;
        this.periods = periods;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** The sheet's periods, by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /** Add a period; the name is stored trimmed. Refused when the name is in use on the sheet, the
     *  to-date is before the from-date, or it shares a day with another period of the sheet. With no
     *  from-date it starts on the sheet's today. A cap, when given, is the most OPEN or PENDING claims a person may
     *  have in it. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate fromDate, LocalDate to, Integer cap) {
        sheets.load(sheetId, user);
        // The sheet is locked so two periods added at the same moment cannot overlap.
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        LocalDate from = fromDate != null ? fromDate : today;
        String trimmed = name.trim();
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts");
        }
        List<Period> existing = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        if (existing.stream().anyMatch(p -> p.getName().equals(trimmed))) {
            throw nameInUse();
        }
        if (existing.stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another on this sheet");
        }
        Period added;
        try {
            added = periods.saveAndFlush(new Period(sheetId, trimmed, from, to, cap));
        } catch (DataIntegrityViolationException raced) {
            throw nameInUse();
        }
        audit.recordChangeOnCommit(sheetId, user, "PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + added.getName()
                + " from=" + added.getFromDate() + " to=" + added.getToDate()
                + (added.getClaimCap() == null ? "" : " cap=" + added.getClaimCap()));
        return added;
    }

    /** Generate one period per calendar month from the from-date's month to the to-date's month, each running
     *  from the first to the last day of its month and named yyyy-mm. A month whose name is already a period, or
     *  whose days overlap a period of the sheet, is left out. Refused when the to-date is before the from-date; with
     *  no from-date it starts on the sheet's today. Returns the periods made, in month order. */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate fromDate, LocalDate to) {
        sheets.load(sheetId, user);
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        LocalDate from = fromDate != null ? fromDate : today;
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date cannot be before the from-date");
        }
        List<Period> existing = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (existing.stream().anyMatch(p -> p.getName().equals(name) || p.overlaps(first, last))) {
                continue;
            }
            Period added = periods.saveAndFlush(new Period(sheetId, name, first, last));
            audit.recordChangeOnCommit(sheetId, user, "PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + added.getName()
                    + " from=" + added.getFromDate() + " to=" + added.getToDate());
            made.add(added);
        }
        return made;
    }

    /** Generate the twelve monthly periods of the fiscal year starting in the given calendar year, from the first of
     *  the sheet's fiscal start month, exactly as {@link #generate} does. Returns the periods made, in month order. */
    @Transactional
    public List<Period> generateFiscalYear(String sheetId, String user, int year) {
        Sheet sheet = sheets.load(sheetId, user);
        YearMonth first = YearMonth.of(year, sheet.getFiscalStartMonth());
        return generate(sheetId, user, first.atDay(1), first.plusMonths(11).atEndOfMonth());
    }

    /** Roll the fiscal year starting in the given calendar year over; only a steward may, and it is refused when the
     *  sheet has no period in that fiscal year. The next fiscal year's twelve periods are generated exactly as
     *  {@link #generateFiscalYear} does, and each person's open figure at the end of the old year's last period, when not
     *  0.00, is added to their opening balance for the new year's first period. Returns the periods made, in month order. */
    @Transactional
    public List<Period> rollover(String sheetId, String user, int year) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may roll the year over");
        }
        List<Period> oldYear = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> sheet.fiscalYearOf(p.getFromDate()) == year).toList();
        if (oldYear.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That fiscal year has no periods on this sheet");
        }
        LocalDate end = oldYear.stream().map(Period::getToDate).max(Comparator.naturalOrder()).orElseThrow();
        Map<Long, BigDecimal> open = rollup.openByPersonAt(sheetId, oldYear, end);
        List<Period> made = generateFiscalYear(sheetId, user, year + 1);
        Period first = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> sheet.fiscalYearOf(p.getFromDate()) == year + 1).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "The next fiscal year has no period to carry into"));
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            BigDecimal amount = open.getOrDefault(person.getId(), BigDecimal.ZERO.setScale(2));
            if (amount.signum() == 0) {
                continue;
            }
            first.addOpening(person.getId(), amount);
            audit.recordChangeOnCommit(sheetId, user, "OPENING_SET sheet=" + sheetId + " by=" + user + " name="
                    + person.getName() + " period=" + first.getName() + " amount=" + amount.toPlainString());
        }
        periods.saveAndFlush(first);
        audit.recordChangeOnCommit(sheetId, user, "ROLLOVER sheet=" + sheetId + " by=" + user + " fy=FY" + year
                + " periods=" + made.size());
        return made;
    }

    /** Generate the four calendar quarters of the given year, named yyyy-Q1 to yyyy-Q4, each running from the first
     *  day of its first month to the last day of its third. A quarter whose name is already a period, or whose days
     *  overlap a period of the sheet, is left out, as {@link #generate} does. Returns the periods made, in order. */
    @Transactional
    public List<Period> generateQuarters(String sheetId, String user, int year) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        List<Period> existing = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        List<Period> made = new ArrayList<>();
        for (int quarter = 1; quarter <= 4; quarter++) {
            String name = year + "-Q" + quarter;
            YearMonth start = YearMonth.of(year, quarter * 3 - 2);
            LocalDate first = start.atDay(1);
            LocalDate last = start.plusMonths(2).atEndOfMonth();
            if (existing.stream().anyMatch(p -> p.getName().equals(name) || p.overlaps(first, last))) {
                continue;
            }
            Period added = periods.saveAndFlush(new Period(sheetId, name, first, last));
            audit.recordChangeOnCommit(sheetId, user, "PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + added.getName()
                    + " from=" + added.getFromDate() + " to=" + added.getToDate());
            made.add(added);
        }
        return made;
    }

    /** Close a period, open or soft-closed, fixing its due-total afresh as it is now; refused for a role the
     *  permission matrix does not allow, and when it is already closed. The sheet is locked so no claim in it changes while its total is taken. */
    @Transactional
    public Period close(String sheetId, String user, String name) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        if (!sheet.allows("close", roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not close a period");
        }
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed");
        }
        return closeAndRecord(sheetId, user, period, "");
    }

    /** Soft-close an open period, fixing its due-total and each person's figure for it as they are now, while its
     *  claims can still be changed; refused for a role the permission matrix does not allow to close, and when it is
     *  already closed or soft-closed. */
    @Transactional
    public Period softClose(String sheetId, String user, String name) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        if (!sheet.allows("close", roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not close a period");
        }
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (period.isClosed() || period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed");
        }
        period.softClose(rollup.dueByPersonBetween(sheetId, period.getFromDate(), period.getToDate()),
                rollup.carriedByPerson(sheetId, period));
        Period closed = periods.saveAndFlush(period);
        audit.recordChangeOnCommit(sheetId, user, "PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + user + " period="
                + closed.getName() + " total=" + closed.getClosedTotal().toPlainString());
        return closed;
    }

    /** Close, in order of their to-dates, every open period of the sheet whose to-date plus its grace days is
     *  before its today, each exactly as a close by hand. Called with the sheet locked, once its new today is set. */
    @Transactional
    public void closePastGrace(Sheet sheet, String user) {
        if (sheet.getGraceDays() == null) {
            return;
        }
        periods.findBySheetIdOrderByFromDateAsc(sheet.getId()).stream()
                .filter(p -> !p.isClosed() && !p.isSoftClosed() && sheet.pastGrace(p.getToDate()))
                .sorted(Comparator.comparing(Period::getToDate))
                .forEach(p -> closeAndRecord(sheet.getId(), user, p, " auto=yes"));
    }

    /** Fix a period's due-total as it is now and record the close; the audit line ends with the suffix given. */
    private Period closeAndRecord(String sheetId, String user, Period period, String suffix) {
        period.close(rollup.dueByPersonBetween(sheetId, period.getFromDate(), period.getToDate()),
                rollup.carriedByPerson(sheetId, period));
        Period closed = periods.saveAndFlush(period);
        BigDecimal total = closed.getClosedTotal();
        audit.recordChangeOnCommit(sheetId, user, "PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + closed.getName()
                + " total=" + total.toPlainString() + suffix);
        return closed;
    }

    /** Reopen a closed or soft-closed period, making its due-total live again; only a role the permission matrix allows may (by
     *  default a steward), and it is refused when the period is open or locked. */
    @Transactional
    public Period reopen(String sheetId, String user, String name) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        if (!sheet.allows("reopen", roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not reopen a period");
        }
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (!period.isClosed() && !period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is not closed");
        }
        if (period.isLocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is locked");
        }
        period.reopen();
        Period reopened = periods.saveAndFlush(period);
        audit.recordChangeOnCommit(sheetId, user, "PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + reopened.getName());
        return reopened;
    }

    /** Lock or unlock a period; only a steward may, and it is refused when the period is already so. */
    @Transactional
    public Period setLocked(String sheetId, String user, String name, boolean locked) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a steward may " + (locked ? "lock" : "unlock") + " a period");
        }
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (period.isLocked() == locked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    locked ? "That period is already locked" : "That period is not locked");
        }
        period.setLocked(locked);
        Period saved = periods.saveAndFlush(period);
        audit.recordChangeOnCommit(sheetId, user, (locked ? "PERIOD_LOCKED" : "PERIOD_UNLOCKED") + " sheet=" + sheetId
                + " by=" + user + " period=" + saved.getName());
        return saved;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet");
    }
}
