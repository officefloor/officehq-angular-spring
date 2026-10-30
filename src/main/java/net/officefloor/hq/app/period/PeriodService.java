package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Permission;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import org.springframework.context.event.EventListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The periods on a sheet, earliest first: none share a name (case ignored) or a date. A closed period
 * keeps the due-total it had when closed, and no claim due by a date inside it can change.
 */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final AccessService access;
    private final ApplicationEventPublisher events;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetRows,
            ClaimRepository claims, PersonRepository people, RateService rates, AccessService access,
            ApplicationEventPublisher events) {
        this.periods = periods;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.access = access;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /**
     * Add a period from {@code from} to {@code to}, both inclusive. Refused when the name is taken on the
     * sheet, the to-date is before the from-date, or it shares a date with another period. Two users adding
     * one at the same moment wait on each other, so no two overlap. A cap, when given, is the most OPEN or PENDING
     * claims a person may have in it, and must be a whole number of at least 1.
     */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to, Integer cap) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (to.isBefore(from)) {
            throw new PeriodRefused("The to-date is before the from-date.");
        }
        if (cap != null && cap < 1) {
            throw new PeriodRefused("The cap must be a whole number of at least 1.");
        }
        if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name)) {
            throw new PeriodRefused("That period name is already in use on this sheet.");
        }
        if (list(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefused("The period overlaps another period.");
        }
        Period period = periods.saveAndFlush(new Period(sheetId, name, from, to, cap));
        events.publishEvent(new PeriodAdded(sheetId, user, name, from, to, cap));
        return period;
    }

    /**
     * Generate one period per calendar month from the from-date's month to the to-date's month, each running
     * from the first to the last day of its month and named yyyy-mm. A month whose name is already a period on
     * the sheet, or whose days overlap one, is left out; the others are still made. Refused when the to-date is
     * before the from-date. Returns the periods made, earliest first.
     */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate from, LocalDate to) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (to.isBefore(from)) {
            throw new PeriodRefused("The to-date is before the from-date.");
        }
        List<Period> existing = new ArrayList<>(list(sheetId));
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            addUnlessTaken(sheetId, user, month.toString(), month.atDay(1), month.atEndOfMonth(), existing, made);
        }
        return made;
    }

    /**
     * Generate the four calendar quarters of the given year, named yyyy-Q1 to yyyy-Q4, each running from the first
     * day of its first month to the last day of its third. A quarter whose name is already a period on the sheet,
     * or whose days overlap one, is left out; the others are still made. Returns the periods made, earliest first.
     */
    @Transactional
    public List<Period> generateQuarters(String sheetId, String user, int year) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        List<Period> existing = new ArrayList<>(list(sheetId));
        List<Period> made = new ArrayList<>();
        for (int quarter = 1; quarter <= 4; quarter++) {
            YearMonth start = YearMonth.of(year, quarter * 3 - 2);
            addUnlessTaken(sheetId, user, String.format("%04d-Q%d", year, quarter), start.atDay(1),
                    start.plusMonths(2).atEndOfMonth(), existing, made);
        }
        return made;
    }

    /** Add a generated period, unless its name is taken or its days overlap one of {@code existing}. */
    private void addUnlessTaken(String sheetId, String user, String name, LocalDate first, LocalDate last,
            List<Period> existing, List<Period> made) {
        if (existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(name) || p.overlaps(first, last))) {
            return;
        }
        Period period = periods.saveAndFlush(new Period(sheetId, name, first, last));
        events.publishEvent(new PeriodAdded(sheetId, user, name, first, last, null));
        existing.add(period);
        made.add(period);
    }

    /** The month (1 to 12) the sheet's fiscal year starts in; 1 unless set. */
    @Transactional(readOnly = true)
    public int fiscalStartMonth(String sheetId) {
        return sheets.settings(sheetId).fiscalStartMonth();
    }

    /**
     * The fiscal year the date falls in, named FY and the calendar year its first day falls in: the year the
     * fiscal year starting in the given month (1 to 12) on or before the date begins.
     */
    public static String fiscalYear(LocalDate date, int startMonth) {
        int year = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        return "FY" + year;
    }

    /**
     * Generate the twelve monthly periods of the fiscal year whose first day falls in the given calendar year,
     * from the sheet's fiscal start month on, exactly as {@link #generate} does. Returns the periods made,
     * earliest first.
     */
    @Transactional
    public List<Period> generateFiscalYear(String sheetId, String user, int year) {
        sheets.load(sheetId, user);
        YearMonth first = YearMonth.of(year, fiscalStartMonth(sheetId));
        return generate(sheetId, user, first.atDay(1), first.plusMonths(11).atEndOfMonth());
    }

    /**
     * Close a period, fixing each person's due-total and carried figure for it (see {@link #carried}), for every person on the sheet, and its own, their sum: the due figures of the claims due by a date inside it that counted (OPEN, PENDING and SETTLED,
     * but not an unbound OPEN claim dated within a hold of its person, nor a deferred one) at the end of its last day,
     * see {@link Dues#countsAsOf}, added up per person. A soft-closed period is closed with its figures fixed
     * afresh. Refused when it is already closed, or for a role the sheet's
     * permission matrix does not allow to close. Takes turns with claim changes on the
     * sheet, so the total fixed is the one the claims had at that moment.
     */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        sheetRows.lockById(sheetId);
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        if (!access.allows(sheetId, user, Permission.CLOSE)) {
            throw new PeriodRefused("Your role may not close a period on this sheet.");
        }
        if (period.isClosed()) {
            throw new PeriodRefused("The period is already closed.");
        }
        fix(sheetId, user, period, false, false);
        return period;
    }

    /**
     * Soft-close an open period: its figures are fixed as a close fixes them, but claims due by a date inside it can
     * still be added, edited, opened, settled and voided. Refused when it is already closed or soft-closed, or
     * for a role the sheet's permission matrix does not allow to close.
     */
    @Transactional
    public Period softClose(String sheetId, String user, long periodId) {
        sheetRows.lockById(sheetId);
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        if (!access.allows(sheetId, user, Permission.CLOSE)) {
            throw new PeriodRefused("Your role may not close a period on this sheet.");
        }
        if (period.isClosed()) {
            throw new PeriodRefused("The period is already closed.");
        }
        if (period.isSoftClosed()) {
            throw new PeriodRefused("The period is already soft-closed.");
        }
        fix(sheetId, user, period, false, true);
        return period;
    }

    /**
     * When the sheet's today is changed and it has grace days, close every open period (neither closed nor
     * soft-closed) whose to-date plus the grace days is before the new today, in order of their to-dates, each as a close by hand would.
     */
    @EventListener
    @Transactional
    public void on(TodayChanged e) {
        Integer grace = sheets.graceDays(e.sheetId());
        if (grace == null) {
            return;
        }
        sheetRows.lockById(e.sheetId());
        list(e.sheetId()).stream()
                .filter(p -> !p.isFixed() && p.getToDate().plusDays(grace).isBefore(e.today()))
                .sorted(Comparator.comparing(Period::getToDate))
                .forEach(p -> fix(e.sheetId(), e.user(), p, true, false));
    }

    /**
     * Each person's carried figure for the period, for every person on the sheet: the due figures as they stand of
     * their claims that count on the sheet's today (neither left out by a hold or retirement nor deferred, see
     * {@link Dues#counts}) due by a date inside a period ending before it starts and still OPEN or PENDING at the end
     * of its last day, by the status log, added up per person, with the opening balances pasted for them in it.
     */
    private Map<Long, BigDecimal> carried(String sheetId, Period period, Map<Long, Person> byId, RateTable table) {
        List<Period> all = list(sheetId);
        Map<Long, BigDecimal> carried = new HashMap<>();
        byId.keySet().forEach(id -> carried.put(id, BigDecimal.ZERO.setScale(2)));
        claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> byId.containsKey(c.getPersonId()))
                .filter(c -> all.stream().anyMatch(home -> home.contains(c.getDueBy())
                        && home.getToDate().isBefore(period.getFromDate())))
                .filter(c -> c.getDeferredUntil() == null || !table.today().isBefore(c.getDeferredUntil()))
                .filter(c -> Dues.countsAsOf(c, byId.get(c.getPersonId()).leaves(), table.today(), period.getToDate()))
                .filter(c -> {
                    ClaimStatus then = Dues.statusAsOf(c, period.getToDate());
                    return then == ClaimStatus.OPEN || then == ClaimStatus.PENDING;
                })
                .forEach(c -> {
                    BigDecimal due = Dues.figure(c, byId.get(c.getPersonId())::gradeOn, table);
                    if (due != null) {
                        carried.merge(c.getPersonId(), due, BigDecimal::add);
                    }
                });
        period.getPersonOpenings().forEach((id, amount) -> {
            if (byId.containsKey(id)) {
                carried.merge(id, amount, BigDecimal::add);
            }
        });
        carried.replaceAll((id, t) -> t.setScale(2, RoundingMode.HALF_UP));
        return carried;
    }

    /** Fix the period's due-totals and carried figures as of the end of its last day and close it, or soft-close it. */
    private void fix(String sheetId, String user, Period period, boolean auto, boolean soft) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, p -> p));
        RateTable table = rates.table(sheetId);
        Map<Long, BigDecimal> totals = new HashMap<>();
        byId.keySet().forEach(id -> totals.put(id, BigDecimal.ZERO.setScale(2)));
        claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> period.contains(c.getDueBy())
                        && Dues.countsAsOf(c, byId.get(c.getPersonId()).leaves(), table.today(), period.getToDate()))
                .forEach(c -> {
                    BigDecimal due = Dues.figure(c, byId.get(c.getPersonId())::gradeOn, table);
                    if (due != null) {
                        totals.merge(c.getPersonId(), due, BigDecimal::add);
                    }
                });
        totals.replaceAll((id, t) -> t.setScale(2, RoundingMode.HALF_UP));
        period.close(totals, carried(sheetId, period, byId, table), soft);
        BigDecimal total = period.getClosedTotal().setScale(2, RoundingMode.HALF_UP);
        periods.flush();
        events.publishEvent(new PeriodClosed(sheetId, user, period.getName(), total, auto, soft));
    }

    /**
     * Reopen a closed or soft-closed period: its due-total and its people's carried figures are live again, the current figures of the claims
     * due by a date inside it. Refused unless the sheet's permission matrix allows the user's role (a steward, unless
     * set), and when the period is neither closed nor soft-closed.
     */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        sheetRows.lockById(sheetId);
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        if (!access.allows(sheetId, user, Permission.REOPEN)) {
            throw new PeriodRefused("Your role may not reopen a period on this sheet.");
        }
        if (!period.isFixed()) {
            throw new PeriodRefused("The period is not closed.");
        }
        if (period.isLocked()) {
            throw new PeriodRefused("The period is locked.");
        }
        period.reopen();
        periods.flush();
        events.publishEvent(new PeriodReopened(sheetId, user, period.getName()));
        return period;
    }

    /**
     * Lock a period, so it cannot be reopened, or unlock it. Refused unless the user is a steward, and when it is
     * already locked (or, to unlock, not locked).
     */
    @Transactional
    public Period lock(String sheetId, String user, long periodId, boolean lock) {
        sheetRows.lockById(sheetId);
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PeriodRefused("Only a steward may " + (lock ? "lock" : "unlock") + " a period.");
        }
        if (period.isLocked() == lock) {
            throw new PeriodRefused(lock ? "The period is already locked." : "The period is not locked.");
        }
        period.setLocked(lock);
        periods.flush();
        events.publishEvent(lock ? new PeriodLocked(sheetId, user, period.getName())
                : new PeriodUnlocked(sheetId, user, period.getName()));
        return period;
    }

    /** The closed period whose dates contain the date, if any. */
    @Transactional(readOnly = true)
    public Optional<Period> closedOn(String sheetId, LocalDate date) {
        return list(sheetId).stream().filter(p -> p.isClosed() && p.contains(date)).findFirst();
    }

    /** The soft-closed period whose dates contain the date, if any. */
    @Transactional(readOnly = true)
    public Optional<Period> softClosedOn(String sheetId, LocalDate date) {
        return list(sheetId).stream().filter(p -> p.isSoftClosed() && p.contains(date)).findFirst();
    }

    /**
     * Refuse a change to a claim due by any of the dates when a closed period contains it. Holds the
     * sheet's lock until the transaction ends, so a period cannot close while the change is made.
     */
    @Transactional
    public void requireOpen(String sheetId, LocalDate... dates) {
        sheetRows.lockById(sheetId);
        for (LocalDate date : dates) {
            closedOn(sheetId, date).ifPresent(p -> {
                throw new ClosedPeriodRefused(p.getName());
            });
        }
    }
}
