package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The periods on a sheet: listed, added, closed and reopened. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final Dues dues;
    private final SheetService sheets;
    private final RoleService roles;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, ClaimRepository claims, PersonRepository people, Dues dues,
            SheetService sheets, RoleService roles, Audit audit) {
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.dues = dues;
        this.sheets = sheets;
        this.roles = roles;
        this.audit = audit;
    }

    /** The sheet's periods by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /**
     * Add a period to the sheet; refused when its from-date is after its to-date, when its name is
     * already in use on the sheet, or when it shares a day with another period.
     */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        // Take the sheet's lock first, so two periods added at once cannot both pass the checks.
        sheets.lock(sheetId);
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period's from-date cannot be after its to-date.");
        }
        if (periods.findBySheetIdAndNameKey(sheetId, Period.keyOf(name)).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet.");
        }
        if (periods.findBySheetIdOrderByFromDateAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period overlaps another period on this sheet.");
        }
        Period period = periods.saveAndFlush(new Period(sheetId, name, from, to));
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + period.getName()
                + " from=" + period.getFromDate() + " to=" + period.getToDate());
        return period;
    }

    /**
     * Generate one period per calendar month from the from-date's month to the to-date's month, each
     * running from the first to the last day of its month and named yyyy-mm, in month order. A month
     * whose name is already a period on the sheet, or whose days overlap one, is left out and the others
     * are still made. Refused when the to-date is before the from-date.
     */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate from, LocalDate to) {
        // Take the sheet's lock first, so no period added meanwhile slips past the checks.
        sheets.lock(sheetId);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date cannot be before the from-date.");
        }
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromDateAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (existing.stream().anyMatch(p -> p.getName().toLowerCase(Locale.ROOT).equals(Period.keyOf(name))
                    || p.overlaps(first, last))) {
                continue;
            }
            Period period = periods.saveAndFlush(new Period(sheetId, name, first, last));
            existing.add(period);
            made.add(period);
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + period.getName()
                    + " from=" + period.getFromDate() + " to=" + period.getToDate());
        }
        return made;
    }

    /**
     * Generate the twelve monthly periods of the fiscal year named FY{@code year}, from the first day of
     * the sheet's fiscal start month in that year, exactly as {@link #generate} does.
     */
    @Transactional
    public List<Period> generateFiscalYear(String sheetId, String user, int year) {
        LocalDate first = sheets.lock(sheetId).fiscalYearStart(year);
        return generate(sheetId, user, first, first.plusYears(1).minusDays(1));
    }

    /**
     * Close a period, fixing at this moment each person's due-total for it and its own, their sum. A person's is the sum of the due figures,
     * as computed now, of the claims due by in it that were OPEN, PENDING or SETTLED at the end of its last day by their status log, each at
     * the grade its person has in force on its date (a claim with no figure adds nothing), leaving out those still OPEN or PENDING and dated
     * within their person's hold or of a person whose retirement date is reached, unless bound by a statement, and those deferred on today. A soft-closed period closes with its figures
     * fixed afresh. Refused when the period is already closed, or for a role the sheet's permission matrix does not allow.
     */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        // Take the sheet's lock first, so no claim in the period changes while its total is fixed.
        LocalDate today = sheets.lock(sheetId).getToday();
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        if (!roles.permits(sheetId, user, "close")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not close a period.");
        }
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed.");
        }
        return fix(sheetId, user, period, today, "");
    }

    /**
     * Soft-close an open period: its due-total and each person's figure for it are fixed as on a close
     * (see {@link #close}), while claims due by in it can still be added and changed. Refused when the
     * period is already closed or soft-closed, or for a role the sheet's permission matrix does not
     * allow to close.
     */
    @Transactional
    public Period softClose(String sheetId, String user, long periodId) {
        LocalDate today = sheets.lock(sheetId).getToday();
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        if (!roles.permits(sheetId, user, "close")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not close a period.");
        }
        if (period.isFixed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed or soft-closed.");
        }
        period.softClose(totals(sheetId, period, today));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + saved.getClosedTotal().toPlainString());
        return saved;
    }

    /**
     * The sheet's today was changed: close, in order of their to-dates, each open period whose to-date
     * plus the sheet's grace days is before the new today, exactly as a close by hand would, its
     * PERIOD_CLOSED marked auto=yes. A soft-closed period keeps the figures fixed for it and is left as
     * it is. Nothing closes while the grace days are off.
     */
    @EventListener
    public void todayChanged(TodayChanged change) {
        if (change.graceDays() == null) {
            return;
        }
        LocalDate today = sheets.lock(change.sheetId()).getToday();
        List<Period> due = periods.findBySheetIdOrderByFromDateAsc(change.sheetId()).stream()
                .filter(p -> !p.isFixed() && p.getToDate().plusDays(change.graceDays()).isBefore(today))
                .sorted(Comparator.comparing(Period::getToDate))
                .toList();
        for (Period period : due) {
            fix(change.sheetId(), change.user(), period, today, " auto=yes");
        }
    }

    /** Close an open or soft-closed period, fixing its totals as of today, and audit it with the given trailing fields. */
    private Period fix(String sheetId, String user, Period period, LocalDate today, String trailing) {
        period.close(totals(sheetId, period, today));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + saved.getClosedTotal().toPlainString() + trailing);
        return saved;
    }

    /** Each person's due-total for the period as of today, by person id (see {@link #close}). */
    private Map<Long, BigDecimal> totals(String sheetId, Period period, LocalDate today) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, person -> person));
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        byId.keySet().forEach(id -> totals.put(id, BigDecimal.ZERO.setScale(2)));
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !period.contains(claim.getDueBy())) {
                continue;
            }
            ClaimStatus then = claim.statusAt(period.getToDate());
            if (then == null || !then.counts() || claim.isDeferredOn(today)
                    || (claim.getStatus().isOpen() && !claim.isStated() && person.leavesLive(claim.getDate(), today))) {
                continue;
            }
            BigDecimal figure = dues.due(claim, person.gradeOn(claim.getDate()));
            if (figure != null) {
                totals.merge(person.getId(), figure, BigDecimal::add);
            }
        }
        return totals;
    }

    /**
     * Reopen a closed or soft-closed period: its due-total is live again, the claims due by in it counting at their
     * current figures. Refused for a role the sheet's permission matrix
     * does not allow (anyone but a steward by default), and when the period is neither closed nor soft-closed.
     */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        LocalDate today = sheets.lock(sheetId).getToday();
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        if (!roles.permits(sheetId, user, "reopen")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not reopen a period.");
        }
        if (!period.isFixed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is not closed.");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + saved.getName());
        return saved;
    }

    /**
     * Give out the number for a claim due by a date (its due-by) as it is opened, holding the sheet's lock until
     * the caller's transaction ends. While the sheet numbers by period (see {@link Sheet#numbersByPeriod})
     * it is the next within the period the date falls in, {@code <period name>-<n>}, or {@code X-<n>}
     * when it falls in none; otherwise it is the next sheet-wide number of the sheet's tree within the
     * fiscal year (by the sheet's fiscal start month) the date falls in, {@code FY<year>-<n>}, after the
     * last given in that fiscal year on any sheet of it. The tree's root is locked too, so the sheets of
     * a tree take their numbers one at a time.
     */
    @Transactional
    public String nextClaimNumber(String sheetId, LocalDate date) {
        Sheet sheet = sheets.lock(sheetId);
        if (!sheet.numbersByPeriod()) {
            List<String> tree = sheets.tree(sheetId);
            sheets.lock(tree.get(0));
            int year = sheet.fiscalYearOf(date);
            int lastInTree = tree.stream().filter(id -> !id.equals(sheetId))
                    .mapToInt(id -> sheets.load(id).getLastClaimNumberIn(year)).max().orElse(0);
            return "FY" + year + "-" + sheet.nextClaimNumberIn(year, lastInTree);
        }
        return periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> p.contains(date))
                .findFirst()
                .map(p -> p.getName() + "-" + p.nextClaimNumber())
                .orElseGet(() -> "X-" + sheet.nextUnperiodClaimNumber());
    }

    /** The name of the period a date falls in on the sheet; null when it falls in none. */
    @Transactional(readOnly = true)
    public String periodOn(String sheetId, LocalDate date) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> p.contains(date))
                .findFirst()
                .map(Period::getName)
                .orElse(null);
    }

    /** Whether the date falls inside one of the sheet's soft-closed periods. */
    @Transactional(readOnly = true)
    public boolean softClosedOn(String sheetId, LocalDate date) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .anyMatch(p -> p.isSoftClosed() && p.contains(date));
    }

    /** Refuse a change to a claim due by a date (its due-by) inside one of the sheet's closed periods. */
    public void refuseIfClosed(String sheetId, LocalDate date) {
        if (periods.findBySheetIdAndClosedTrue(sheetId).stream().anyMatch(p -> p.contains(date))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is due by inside a closed period.");
        }
    }
}
