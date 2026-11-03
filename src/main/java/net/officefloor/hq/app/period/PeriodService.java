package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolService;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The periods on a sheet: named, each running from a day to a day, none overlapping another. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final Dues dues;
    private final RoleService roles;
    private final History history;
    private final Audit audit;
    private final PoolService pools;
    private final RolloverRepository rollovers;

    public PeriodService(PeriodRepository periods, SheetService sheets, ClaimRepository claims,
            PersonRepository people, Dues dues, RoleService roles, History history, Audit audit,
            @Lazy PoolService pools, RolloverRepository rollovers) {
        this.pools = pools;
        this.rollovers = rollovers;
        this.history = history;
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.dues = dues;
        this.roles = roles;
        this.audit = audit;
    }

    /** The periods on the sheet, earliest first. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /** Add a period, capping each person's open or pending claims in it when a cap is given (null:
     *  none); refused when its to-date is before its from-date, its cap is below 1, its name is in use
     *  on the sheet, or it shares a day with another period. */
    @Transactional
    public synchronized Period add(String sheetId, String user, String name, LocalDate from, LocalDate to,
            Integer cap) {
        sheets.load(sheetId, user);
        if (cap != null && cap < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period's claim cap must be at least 1");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts");
        }
        if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name)) {
            throw nameInUse();
        }
        if (periods.overlaps(sheetId, from, to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another on this sheet");
        }
        Period saved;
        try {
            saved = periods.saveAndFlush(new Period(sheetId, name, from, to, cap));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " from=" + saved.getFromDate() + " to=" + saved.getToDate()
                + (saved.getClaimCap() == null ? "" : " cap=" + saved.getClaimCap()));
        history.record(sheetId);
        return saved;
    }

    /** Generate one period per calendar month from the from-date's month to the to-date's month
     *  (the from-date the sheet's today when none is given), each running from the first to the last
     *  day of its month and named yyyy-mm; a month whose name is in use on the sheet, or whose days
     *  overlap a period on it, is left out. Refused when the to-date is before the from-date. A
     *  viewer's generate makes none. */
    @Transactional
    public synchronized List<Period> generate(String sheetId, String user, LocalDate from, LocalDate to) {
        sheets.load(sheetId, user);
        if (from == null) {
            from = sheets.today(sheetId);
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date cannot be before the from-date");
        }
        if (SheetRole.VIEWER.equals(roles.roleOf(sheetId, user))) {
            return List.of();
        }
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name) || periods.overlaps(sheetId, first, last)) {
                continue;
            }
            Period saved = periods.saveAndFlush(new Period(sheetId, name, first, last));
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                    + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
            made.add(saved);
        }
        history.record(sheetId);
        return made;
    }

    /** Generate the twelve monthly periods of the fiscal year named by the year, as {@link #generate}
     *  does, from the first day of the sheet's fiscal year start month in that year. */
    @Transactional
    public synchronized List<Period> generateFiscalYear(String sheetId, String user, int year) {
        LocalDate first = LocalDate.of(year, sheets.fiscalStartMonth(sheetId), 1);
        return generate(sheetId, user, first, first.plusMonths(12).minusDays(1));
    }

    /** What a rollover came to: the periods it generated, and every period it changed (those made
     *  and the new year's first period, whose openings it added to). */
    public record RolledOver(List<Period> made, List<Period> changed) {
    }

    /** Roll the fiscal year named by the year over: generate the next fiscal year's twelve periods as
     *  {@link #generateFiscalYear} does, then add each person's open figure at the end of the old
     *  year's last period to their opening balance for the new year's first period. A person's open
     *  figure is the due of their claims that count, whose due-by falls in a period of the old year,
     *  and that were OPEN or PENDING at that last period's end; what an earlier rollover of the same
     *  year, not undone, added there is taken off it. The rollover is kept so it can be undone
     *  ({@link #undoRollover}). Refused unless the user acts as a steward, or when the old year has
     *  no periods. */
    @Transactional
    public synchronized RolledOver rollover(String sheetId, String user, int year) {
        sheets.load(sheetId, user);
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may roll a year over");
        }
        int fiscalStartMonth = sheets.fiscalStartMonth(sheetId);
        String oldFy = "FY" + year;
        List<Period> oldYear = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> fiscalYear(p.getFromDate(), fiscalStartMonth).equals(oldFy)).toList();
        if (oldYear.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That fiscal year has no periods");
        }
        LocalDate end = oldYear.stream().map(Period::getToDate).max(LocalDate::compareTo).orElseThrow();
        List<Period> made = generateFiscalYear(sheetId, user, year + 1);
        String newFy = "FY" + (year + 1);
        Period first = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> fiscalYear(p.getFromDate(), fiscalStartMonth).equals(newFy)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "The next fiscal year has no first period"));
        List<Person> onSheet = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, BigDecimal> open = new HashMap<>();
        LocalDate today = sheets.today(sheetId);
        for (Claim c : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            Person person = onSheet.stream().filter(p -> p.getId().equals(c.getPersonId())).findFirst().orElse(null);
            boolean inOldYear = oldYear.stream().anyMatch(p -> !c.getDueBy().isBefore(p.getFromDate())
                    && !c.getDueBy().isAfter(p.getToDate()));
            if (person == null || !inOldYear || !Dues.countsAsOf(person, c, end, today)) {
                continue;
            }
            ClaimStatus then = c.statusOn(end);
            if (then != ClaimStatus.OPEN && then != ClaimStatus.PENDING) {
                continue;
            }
            BigDecimal figure = dues.figure(sheetId, person, c);
            if (figure != null) {
                open.merge(person.getId(), figure, BigDecimal::add);
            }
        }
        Period locked = periods.lockByIdAndSheetId(first.getId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        // what an earlier rollover of the year, not undone, already set there is not set again
        Map<Long, BigDecimal> already = new HashMap<>();
        for (Rollover earlier : rollovers.findBySheetIdAndFyAndUndoneFalse(sheetId, oldFy)) {
            if (earlier.getOpeningPeriodId().equals(locked.getId())) {
                earlier.getOpenings().forEach((personId, amount) -> already.merge(personId, amount, BigDecimal::add));
            }
        }
        Map<Long, BigDecimal> openings = new HashMap<>();
        for (Person person : onSheet) {
            BigDecimal amount = open.getOrDefault(person.getId(), BigDecimal.ZERO)
                    .subtract(already.getOrDefault(person.getId(), BigDecimal.ZERO));
            if (amount.signum() == 0) {
                continue;
            }
            amount = amount.setScale(2);
            locked.addOpening(person.getId(), amount);
            openings.put(person.getId(), amount);
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                    + " period=" + locked.getName() + " amount=" + amount.toPlainString());
        }
        Period saved = periods.saveAndFlush(locked);
        rollovers.save(new Rollover(sheetId, user, today, oldFy, saved.getId(),
                made.stream().map(Period::getId).collect(Collectors.toSet()), openings));
        audit.recordOnCommit("ROLLOVER sheet=" + sheetId + " by=" + user + " fy=" + oldFy + " periods=" + made.size());
        history.record(sheetId);
        List<Period> changed = new ArrayList<>(made.stream().filter(p -> !p.getId().equals(saved.getId())).toList());
        changed.add(saved);
        return new RolledOver(made, changed);
    }

    /** What undoing a rollover came to: the ids of the periods it removed, and the period whose
     *  opening balances it took back (null when that period was one it removed). */
    public record RolloverUndone(List<Long> removed, Period changed) {
    }

    /** Undo the last rollover made on the sheet: remove the periods it generated and take back the
     *  opening balances it set. Refused unless the user is a steward and the one who made it, on the
     *  sheet-today it was made, it is not already undone, and no claim on the sheet now belongs to
     *  (is due by a day inside) a period it generated. */
    @Transactional
    public synchronized RolloverUndone undoRollover(String sheetId, String user) {
        sheets.load(sheetId, user);
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may undo a rollover");
        }
        Rollover last = rollovers.findFirstBySheetIdOrderByIdDesc(sheetId)
                .filter(r -> !r.isUndone())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "There is no rollover to undo"));
        if (!last.getMadeBy().equals(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the steward who made the rollover may undo it");
        }
        if (!last.getMadeOn().equals(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A rollover can only be undone on the day it was made");
        }
        List<Period> generated = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> last.getPeriodIds().contains(p.getId())).toList();
        for (Claim c : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            boolean inGenerated = generated.stream().anyMatch(p -> !c.getDueBy().isBefore(p.getFromDate())
                    && !c.getDueBy().isAfter(p.getToDate()));
            if (inGenerated) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A claim now belongs to a period the rollover generated");
            }
        }
        Period changed = null;
        if (!last.getPeriodIds().contains(last.getOpeningPeriodId())) {
            Period first = periods.lockByIdAndSheetId(last.getOpeningPeriodId(), sheetId).orElse(null);
            if (first != null) {
                last.getOpenings().forEach((personId, amount) -> first.addOpening(personId, amount.negate()));
                first.dropZeroOpenings();
                changed = periods.saveAndFlush(first);
            }
        }
        periods.deleteAll(generated);
        periods.flush();
        last.undo();
        rollovers.save(last);
        audit.recordOnCommit("ROLLOVER_UNDONE sheet=" + sheetId + " by=" + user + " fy=" + last.getFy());
        history.record(sheetId);
        return new RolloverUndone(generated.stream().map(Period::getId).toList(), changed);
    }

    /** Generate the four calendar quarters of the year, each running from the first day of its
     *  first month to the last day of its third month and named yyyy-Qn; a quarter whose name is in
     *  use on the sheet, or whose days overlap a period on it, is left out. */
    @Transactional
    public synchronized List<Period> generateQuarters(String sheetId, String user, int year) {
        sheets.load(sheetId, user);
        List<Period> made = new ArrayList<>();
        for (int quarter = 1; quarter <= 4; quarter++) {
            String name = year + "-Q" + quarter;
            LocalDate first = LocalDate.of(year, quarter * 3 - 2, 1);
            LocalDate last = YearMonth.of(year, quarter * 3).atEndOfMonth();
            if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name) || periods.overlaps(sheetId, first, last)) {
                continue;
            }
            Period saved = periods.saveAndFlush(new Period(sheetId, name, first, last));
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                    + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
            made.add(saved);
        }
        history.record(sheetId);
        return made;
    }

    /** The fiscal year a day falls in (FYyyyy), with fiscal years starting on the first day of the
     *  month: named by the calendar year that first day falls in. */
    public static String fiscalYear(LocalDate day, int fiscalStartMonth) {
        return "FY" + (day.getMonthValue() >= fiscalStartMonth ? day.getYear() : day.getYear() - 1);
    }

    /** Close a period (an open or a soft-closed one), fixing its due-total at what it is now; refused when the permission matrix does
     *  not allow the user's role to close, or it is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        sheets.load(sheetId, user);
        if (!roles.may(sheetId, user, "close")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not close a period");
        }
        Period period = periods.lockByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed");
        }
        return closeOpen(sheetId, user, period, "");
    }

    /** Soft-close a period, fixing its due-total and each person's for it at what they are now while
     *  claims dated inside it stay open to change; refused when the permission matrix does not allow
     *  the user's role to close, or it is already closed or soft-closed. */
    @Transactional
    public Period softClose(String sheetId, String user, long periodId) {
        sheets.load(sheetId, user);
        if (!roles.may(sheetId, user, "close")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not close a period");
        }
        Period period = periods.lockByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (period.isClosed() || period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed");
        }
        period.softClose(personTotals(sheetId, period), personCarried(sheetId, period),
                pools.currentCuts(sheetId));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + saved.getClosedTotal().toPlainString());
        history.record(sheetId);
        return saved;
    }

    /** Close every open period on the sheet whose to-date plus the grace days is before the day,
     *  earliest to-date first, each as a close by hand would, its record marked auto=yes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void autoClose(String sheetId, String user, int graceDays, LocalDate day) {
        for (Period period : periods.findOpenEndingBefore(sheetId, day.minusDays(graceDays))) {
            closeOpen(sheetId, user, period, " auto=yes");
        }
    }

    /** Close an open period, fixing its due-total at what it is now; the suffix ends its record. */
    private Period closeOpen(String sheetId, String user, Period period, String suffix) {
        period.close(personTotals(sheetId, period), personCarried(sheetId, period),
                pools.currentCuts(sheetId));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + saved.getClosedTotal().toPlainString() + suffix);
        history.record(sheetId);
        return saved;
    }

    /** Reopen a closed or soft-closed period, making its due-total live again; refused when the permission matrix
     *  does not allow the user's role to reopen, the period is not closed, or it is locked. */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        sheets.load(sheetId, user);
        if (!roles.may(sheetId, user, "reopen")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not reopen a period");
        }
        Period period = periods.lockByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (!period.isClosed() && !period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is not closed");
        }
        if (period.isLocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is locked");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + saved.getName());
        history.record(sheetId);
        return saved;
    }

    /** Lock or unlock a period; refused unless the user acts as a steward, or when it is already
     *  locked (or, to unlock, not locked). */
    @Transactional
    public Period lock(String sheetId, String user, long periodId, boolean lock) {
        sheets.load(sheetId, user);
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a steward may " + (lock ? "lock" : "unlock") + " a period");
        }
        Period period = periods.lockByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (period.isLocked() == lock) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    lock ? "That period is already locked" : "That period is not locked");
        }
        period.setLocked(lock);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit((lock ? "PERIOD_LOCKED" : "PERIOD_UNLOCKED") + " sheet=" + sheetId + " by=" + user
                + " period=" + saved.getName());
        history.record(sheetId);
        return saved;
    }

    /** Each person's due-total for a period, by the person's id: the due figures of their claims
     *  whose due-by falls in it and that count by their status at its end, skipping those with no due
     *  and those the live rules leave out (see Dues#countsAsOf). */
    private Map<Long, BigDecimal> personTotals(String sheetId, Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (Long id : byId.keySet()) {
            totals.put(id, BigDecimal.ZERO.setScale(2));
        }
        LocalDate today = sheets.today(sheetId);
        for (Claim c : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            Person person = byId.get(c.getPersonId());
            if (person == null || !Dues.countsAsOf(person, c, period.getToDate(), today) || c.getDueBy().isBefore(period.getFromDate())
                    || c.getDueBy().isAfter(period.getToDate())) {
                continue;
            }
            BigDecimal figure = dues.figure(sheetId, person, c);
            if (figure != null) {
                totals.merge(person.getId(), figure, BigDecimal::add);
            }
        }
        return totals;
    }

    /** Each person's carried figure for a period, by the person's id: their opening balance for it
     *  plus the due figures of their claims that count by their status at its end whose due-by falls
     *  in a period ending before it starts and that were still open (or pending) at its end, skipping
     *  those with no due. */
    private Map<Long, BigDecimal> personCarried(String sheetId, Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        List<Period> all = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        Map<Long, BigDecimal> carried = new HashMap<>();
        for (Long id : byId.keySet()) {
            carried.put(id, period.getPersonOpenings().getOrDefault(id, BigDecimal.ZERO).setScale(2));
        }
        LocalDate today = sheets.today(sheetId);
        for (Claim c : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            Person person = byId.get(c.getPersonId());
            boolean earlier = all.stream().anyMatch(p -> !c.getDueBy().isBefore(p.getFromDate())
                    && !c.getDueBy().isAfter(p.getToDate()) && p.getToDate().isBefore(period.getFromDate()));
            if (person == null || !earlier || !Dues.countsAsOf(person, c, period.getToDate(), today)) {
                continue;
            }
            ClaimStatus then = c.statusOn(period.getToDate());
            if (then != ClaimStatus.OPEN && then != ClaimStatus.PENDING) {
                continue;
            }
            BigDecimal figure = dues.figure(sheetId, person, c);
            if (figure != null) {
                carried.merge(person.getId(), figure, BigDecimal::add);
            }
        }
        return carried;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet");
    }
}
