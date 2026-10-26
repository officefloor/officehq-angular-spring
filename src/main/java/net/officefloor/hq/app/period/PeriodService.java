package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PersonRepository people;
    private final SettingService settings;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetRows,
            ClaimRepository claims, ClaimService claimService, PersonRepository people, SettingService settings,
            Audit audit) {
        this.settings = settings;
        this.periods = periods;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
        this.audit = audit;
    }

    /** The sheet's periods in date order. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** Add a period, refused when its name is in use on the sheet, its to-date is before its
     *  from-date, or it shares a day with another of the sheet's periods. The cap, when given, is how many
     *  OPEN or PENDING claims a person may have in it; null for none. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to, Integer cap) {
        sheets.load(sheetId);
        // Periods of a sheet are added one at a time, so two added at once cannot overlap.
        sheetRows.findForUpdate(sheetId);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date.");
        }
        if (periods.existsBySheetIdAndNameKey(sheetId, Period.keyOf(name))) {
            throw nameInUse();
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period overlaps another period.");
        }
        Period saved;
        try {
            saved = periods.saveAndFlush(new Period(sheetId, name, from, to, cap));
        } catch (DataIntegrityViolationException raced) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " from=" + saved.getFrom() + " to=" + saved.getTo()
                + (saved.getCap() == null ? "" : " cap=" + saved.getCap()));
        return saved;
    }

    /** Generate one period per calendar month from the from-date's month to the to-date's month, each
     *  from the first to the last day of its month and named yyyy-mm; a blank date is the sheet's today.
     *  A month whose name is in use on the sheet, or whose days overlap one of its periods, is left out.
     *  Refused when the to-date is before the from-date. The periods made, in date order. */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate from, LocalDate to) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        LocalDate today = sheets.today(sheetId);
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? today : to;
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date.");
        }
        return generateMonths(sheetId, user, YearMonth.from(start), YearMonth.from(end));
    }

    /** Generate the twelve monthly periods of a fiscal year, as {@link #generate} does from the first day of
     *  the year to its last: the year named FY&lt;year&gt; starts on the first day of the sheet's fiscal start
     *  month in that calendar year. The periods made, in date order. */
    @Transactional
    public List<Period> generateFiscalYear(String sheetId, String user, int year) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        YearMonth first = YearMonth.of(year, settings.fiscalStartMonth(sheetId));
        return generateMonths(sheetId, user, first, first.plusMonths(11));
    }

    /** Generate the four calendar quarters of a year, named &lt;yyyy&gt;-Q1 to &lt;yyyy&gt;-Q4, each as the
     *  period form would add it; a quarter whose name is in use on the sheet, or whose days overlap one of
     *  its periods, is left out. The periods made, in date order. */
    @Transactional
    public List<Period> generateQuarters(String sheetId, String user, int year) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (int quarter = 1; quarter <= 4; quarter++) {
            YearMonth firstMonth = YearMonth.of(year, quarter * 3 - 2);
            makeUnlessTaken(sheetId, user, existing, made, year + "-Q" + quarter,
                    firstMonth.atDay(1), firstMonth.plusMonths(2).atEndOfMonth());
        }
        return made;
    }

    /** Make one period per month from the first month to the last, leaving out those whose name is in use
     *  or whose days overlap a period; the periods made, in date order. */
    private List<Period> generateMonths(String sheetId, String user, YearMonth firstMonth, YearMonth lastMonth) {
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (YearMonth month = firstMonth; !month.isAfter(lastMonth); month = month.plusMonths(1)) {
            makeUnlessTaken(sheetId, user, existing, made, month.toString(), month.atDay(1), month.atEndOfMonth());
        }
        return made;
    }

    /** Make a generated period unless its name is in use or its days overlap one of the existing periods,
     *  adding it to both the existing and the made periods. */
    private void makeUnlessTaken(String sheetId, String user, List<Period> existing, List<Period> made,
            String name, LocalDate first, LocalDate last) {
        if (existing.stream().anyMatch(p -> p.getNameKey().equals(Period.keyOf(name)) || p.overlaps(first, last))) {
            return;
        }
        Period saved = periods.saveAndFlush(new Period(sheetId, name, first, last));
        existing.add(saved);
        made.add(saved);
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " from=" + saved.getFrom() + " to=" + saved.getTo());
    }

    /** Close a period, fixing at this moment its due-total and each person's due-total for it: the figures as shown now of the claims
     *  due by a date in it that were open or settled at the end of its last day (by their status logs), skipping those that are n/a and the unbound open claims of a person whose retirement date is reached, and the claims deferred on the sheet's today; and each person's carried figure for it: the figures as shown now of their claims counted so that belong to a period ending before it starts and were still open (PENDING included) at its end, with their opening balance for it. A soft-closed period is closed with its figures fixed afresh. Refused when the period is already closed, or the sheet's permission matrix does not allow the user's role to close. */
    @Transactional
    public Period close(String sheetId, String user, long id) {
        // Locked as claim changes lock it, so no claim in the period changes while it is closed.
        sheetRows.findForUpdate(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        settings.refuseUnlessPermitted(sheetId, user, "close", "Your role may not close a period on this sheet.");
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed.");
        }
        return closeNow(sheetId, user, period, false, false);
    }

    /** Soft-close a period: its due-total and each person's due-total for it are fixed at this moment as
     *  a close fixes them, while claims dated in it can still be added and changed. Refused when the
     *  period is closed or soft-closed already, or the sheet's permission matrix does not allow the
     *  user's role to close. */
    @Transactional
    public Period softClose(String sheetId, String user, long id) {
        sheetRows.findForUpdate(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        settings.refuseUnlessPermitted(sheetId, user, "close", "Your role may not close a period on this sheet.");
        if (period.isClosed() || period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed.");
        }
        return closeNow(sheetId, user, period, false, true);
    }

    /** Once the sheet's today is changed, close each open period whose to-date plus the sheet's grace days
     *  is before the new today (a soft-closed period keeps the figures fixed when it was soft-closed), in order of their to-dates, as a close by hand would; none when the grace
     *  days are blank. Each is audited as closed automatically. */
    @EventListener
    @Transactional
    public void closeOverdue(TodayChanged change) {
        Integer grace = settings.graceDays(change.sheetId());
        if (grace == null) return;
        sheetRows.findForUpdate(change.sheetId());
        List<Period> due = periods.findBySheetIdOrderByFromAsc(change.sheetId()).stream()
                .filter((p) -> !p.isClosed() && !p.isSoftClosed() && p.getTo().plusDays(grace).isBefore(change.today()))
                .sorted(Comparator.comparing(Period::getTo)).toList();
        for (Period period : due) closeNow(change.sheetId(), change.user(), period, true, false);
    }

    /** Close (or soft-close) a period now, fixing its figures; the audit line marks a close the today change made. */
    private Period closeNow(String sheetId, String user, Period period, boolean auto, boolean soft) {
        Map<Long, Person> peopleById = people.findInListOrder(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        LocalDate today = sheets.today(sheetId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        Map<Long, BigDecimal> personTotals = new HashMap<>();
        Map<Long, BigDecimal> personCarried = new HashMap<>();
        for (Long personId : peopleById.keySet()) {
            personTotals.put(personId, BigDecimal.ZERO.setScale(2));
            personCarried.put(personId, period.openingOf(personId));
        }
        List<Period> all = periods.findBySheetIdOrderByFromAsc(sheetId);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = peopleById.get(claim.getPersonId());
            if (person == null) continue;
            // Counted by the status the claim had at the end of the period's last day; a reached retirement
            // only leaves out a claim still open now, as it touches only live claims. A hold leaves none out.
            ClaimStatus then = claim.statusAsOf(period.getTo());
            boolean bound = claim.isStated();
            boolean counts = then != null && !claim.isDeferredOn(today) && (then == ClaimStatus.SETTLED
                    || (then.isOpen() && (bound || !claim.getStatus().isOpen() || !person.isRetiredBy(today))));
            if (!counts) continue;
            // Carried: still open at the end of this period, belonging to a period ending before it starts.
            boolean carried = then.isOpen() && all.stream()
                    .anyMatch((p) -> p.contains(claim.getDueBy()) && p.getTo().isBefore(period.getFrom()));
            if (!carried && !period.contains(claim.getDueBy())) continue;
            BigDecimal due = claimService.due(sheetId, person, claim);
            if (due == null) continue;
            if (carried) {
                personCarried.merge(person.getId(), due, BigDecimal::add);
            } else {
                total = total.add(due);
                personTotals.merge(person.getId(), due, BigDecimal::add);
            }
        }
        if (soft) period.softClose(total, personTotals, personCarried);
        else period.close(total, personTotals, personCarried);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit((soft ? "PERIOD_SOFT_CLOSED" : "PERIOD_CLOSED") + " sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + total.toPlainString() + (auto ? " auto=yes" : ""));
        return saved;
    }

    /** Reopen a closed or soft-closed period, making its due-total live again. Refused when the sheet's permission
     *  matrix does not allow the user's role to reopen (by default, only a steward), or the period is not closed. */
    @Transactional
    public Period reopen(String sheetId, String user, long id) {
        // Locked as closing locks it, so a reopen and a close of the period do not cross.
        sheetRows.findForUpdate(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        settings.refuseUnlessPermitted(sheetId, user, "reopen", "Your role may not reopen a period on this sheet.");
        if (period.isLocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is locked.");
        }
        if (!period.isClosed() && !period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is not closed.");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + saved.getName());
        return saved;
    }

    /** Lock or unlock a period; a locked period cannot be reopened. Refused unless the user acts as a
     *  steward, and when the period is already locked (or, to unlock, not locked). */
    @Transactional
    public Period setLocked(String sheetId, String user, long id, boolean locked) {
        sheetRows.findForUpdate(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may lock or unlock a period.");
        }
        if (period.isLocked() == locked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    locked ? "The period is already locked." : "The period is not locked.");
        }
        period.setLocked(locked);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit((locked ? "PERIOD_LOCKED" : "PERIOD_UNLOCKED") + " sheet=" + sheetId + " by=" + user
                + " period=" + saved.getName());
        return saved;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on the sheet.");
    }
}
