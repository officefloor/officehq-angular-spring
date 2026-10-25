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

    /** Make one period per month from the first month to the last, leaving out those whose name is in use
     *  or whose days overlap a period; the periods made, in date order. */
    private List<Period> generateMonths(String sheetId, String user, YearMonth firstMonth, YearMonth lastMonth) {
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (YearMonth month = firstMonth; !month.isAfter(lastMonth); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (existing.stream().anyMatch(p -> p.getNameKey().equals(Period.keyOf(name)) || p.overlaps(first, last))) {
                continue;
            }
            Period saved = periods.saveAndFlush(new Period(sheetId, name, first, last));
            existing.add(saved);
            made.add(saved);
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                    + " from=" + saved.getFrom() + " to=" + saved.getTo());
        }
        return made;
    }

    /** Close a period, fixing at this moment its due-total and each person's due-total for it: the figures as shown now of the claims
     *  due by a date in it that were open or settled at the end of its last day (by their status logs), skipping those that are n/a and the unbound open claims dated within a hold of their person, and the claims deferred on the sheet's today. A soft-closed period is closed with its figures fixed afresh. Refused when the period is already closed, or the sheet's permission matrix does not allow the user's role to close. */
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
        for (Long personId : peopleById.keySet()) personTotals.put(personId, BigDecimal.ZERO.setScale(2));
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = peopleById.get(claim.getPersonId());
            if (person == null) continue;
            // Counted by the status the claim had at the end of the period's last day; a hold only leaves
            // out a claim still open now, as holds touch only live claims; so does a reached retirement.
            ClaimStatus then = claim.statusAsOf(period.getTo());
            boolean bound = claim.isStated() && then != ClaimStatus.PENDING;
            boolean counts = then != null && !claim.isDeferredOn(today) && (then == ClaimStatus.SETTLED
                    || (then.isOpen() && (bound || !claim.getStatus().isOpen() || !person.isLeftOut(claim.getDate(), today))));
            if (!counts || !period.contains(claim.getDueBy())) continue;
            BigDecimal due = claimService.due(sheetId, person, claim);
            if (due != null) {
                total = total.add(due);
                personTotals.merge(person.getId(), due, BigDecimal::add);
            }
        }
        if (soft) period.softClose(total, personTotals);
        else period.close(total, personTotals);
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
        if (!period.isClosed() && !period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is not closed.");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + saved.getName());
        return saved;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on the sheet.");
    }
}
