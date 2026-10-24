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
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Action;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The periods of each sheet: uniquely named, none overlapping another. A period can be closed, which
 * fixes its due-total then, and a steward (or the roles the permission matrix allows) can reopen it, which makes its due-total live again. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final PersonRepository people;
    private final RollupService rollup;
    private final SettingsService settings;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, ClaimRepository claims,
            ClaimLogRepository logs, PersonRepository people, RollupService rollup, SettingsService settings, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.logs = logs;
        this.people = people;
        this.rollup = rollup;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's periods, by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        return add(sheetId, user, name, from, to, null);
    }

    /** Add a period capping each person's OPEN or PENDING claims in it at {@code cap} (none when null);
     * a cap below one is refused. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to, Integer cap) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        if (to.isBefore(from)) {
            throw new PeriodRefusedException("to before from");
        }
        if (cap != null && cap < 1) {
            throw new PeriodRefusedException("cap below one");
        }
        if (periods.findBySheetIdAndNameKey(sheetId, Period.keyOf(name)).isPresent()) {
            throw new PeriodRefusedException("name taken");
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefusedException("overlaps");
        }
        Period period = periods.saveAndFlush(new Period(sheetId, name, from, to, cap));
        List<String> fields = new ArrayList<>(List.of("period=" + period.getName(), "from=" + period.getFrom(),
                "to=" + period.getTo()));
        if (cap != null) fields.add("cap=" + cap);
        audit.change("PERIOD_ADDED", sheetId, user, fields.toArray(String[]::new));
        return period;
    }

    /** Make one period per calendar month from the from-date's month to the to-date's month, each from
     * the first to the last day of its month and named yyyy-mm, in order; a month whose name is
     * already a period's or whose days overlap a period on the sheet is left out. A missing date is
     * the sheet's today; a to-date before the from-date is refused. */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate from, LocalDate to) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        LocalDate start = from == null ? sheet.getToday() : from;
        LocalDate end = to == null ? sheet.getToday() : to;
        if (end.isBefore(start)) {
            throw new PeriodRefusedException("to before from");
        }
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(start); !month.isAfter(YearMonth.from(end)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            String key = Period.keyOf(name);
            if (existing.stream().anyMatch(p -> Period.keyOf(p.getName()).equals(key) || p.overlaps(first, last))) {
                continue;
            }
            Period period = periods.saveAndFlush(new Period(sheetId, name, first, last));
            audit.change("PERIOD_ADDED", sheetId, user, "period=" + period.getName(), "from=" + period.getFrom(),
                    "to=" + period.getTo());
            existing.add(period);
            made.add(period);
        }
        return made;
    }

    /** Make the twelve monthly periods of the fiscal year FY{@code year}, the one whose first day is the
     * first of the sheet's fiscal start month in {@code year}, exactly as {@link #generate} would. */
    @Transactional
    public List<Period> generateFiscalYear(String sheetId, String user, int year) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        YearMonth first = YearMonth.of(year, sheet.getFiscalStartMonth());
        return generate(sheetId, user, first.atDay(1), first.plusMonths(11).atEndOfMonth());
    }

    /** Make the four calendar quarters of {@code year}, named yyyy-Q1 to yyyy-Q4, in order, each as
     * {@link #add} would add it; a quarter whose name is already a period's or whose days overlap a
     * period on the sheet is left out. */
    @Transactional
    public List<Period> generateQuarters(String sheetId, String user, int year) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (int quarter = 1; quarter <= 4; quarter++) {
            String name = year + "-Q" + quarter;
            LocalDate first = YearMonth.of(year, quarter * 3 - 2).atDay(1);
            LocalDate last = YearMonth.of(year, quarter * 3).atEndOfMonth();
            String key = Period.keyOf(name);
            if (existing.stream().anyMatch(p -> Period.keyOf(p.getName()).equals(key) || p.overlaps(first, last))) {
                continue;
            }
            Period period = add(sheetId, user, name, first, last);
            existing.add(period);
            made.add(period);
        }
        return made;
    }

    /** Close the period, fixing its due-total and each person's carried figure into it now (afresh when it was soft-closed); refused when the
     * user's role may not close (see {@link SettingsService#allowed}) or it is already closed, not
     * only soft-closed. */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        Period period = periods.lockByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (!settings.allowed(sheetId, user, Action.CLOSE)) {
            throw new PeriodNotStewardException();
        }
        if (period.isClosed() && !period.isSoft()) {
            throw new PeriodRefusedException("already closed");
        }
        return close(period, user);
    }

    /** Soft-close the period, fixing its due-total now while claims dated in it can still change;
     * refused as {@link #close(String, String, long)} is, and when it is already closed or soft-closed. */
    @Transactional
    public Period softClose(String sheetId, String user, long periodId) {
        Period period = periods.lockByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (!settings.allowed(sheetId, user, Action.CLOSE)) {
            throw new PeriodNotStewardException();
        }
        if (period.isClosed()) {
            throw new PeriodRefusedException("already closed");
        }
        period.close(personTotalsOf(period), personCarriedOf(period), true);
        periods.flush();
        audit.change("PERIOD_SOFT_CLOSED", sheetId, user, "period=" + period.getName(),
                "total=" + period.getClosedTotal().toPlainString());
        return period;
    }

    /** Close, as {@link #close(String, String, long)} would, every open period of the sheet whose to-date
     * plus its grace days is before its today, in order of their to-dates, each marked auto=yes; nothing
     * when the grace days are off. An automatic close is not subject to the permission matrix. */
    @Transactional
    public void closeOverdue(String sheetId, String user) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        Integer grace = sheet.getGraceDays();
        if (grace == null) return;
        List<Period> due = periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> !p.isClosed() && p.getTo().plusDays(grace).isBefore(sheet.getToday()))
                .sorted(Comparator.comparing(Period::getTo)).toList();
        for (Period open : due) {
            Period period = periods.lockByIdAndSheetId(open.getId(), sheetId).orElseThrow(PeriodNotFoundException::new);
            if (!period.isClosed()) close(period, user, "auto=yes");
        }
    }

    private Period close(Period period, String user, String... extra) {
        period.close(personTotalsOf(period), personCarriedOf(period));
        periods.flush();
        List<String> fields = new ArrayList<>(List.of("period=" + period.getName(),
                "total=" + period.getClosedTotal().toPlainString()));
        fields.addAll(List.of(extra));
        audit.change("PERIOD_CLOSED", period.getSheetId(), user, fields.toArray(String[]::new));
        return period;
    }

    /** Reopen the period, making its due-total and the carried figures into it live again; refused when the user's role may not
     * reopen (a steward's by default, see {@link SettingsService#allowed}) or the period is not closed
     * or soft-closed, or it is locked. */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        Period period = periods.lockByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (!settings.allowed(sheetId, user, Action.REOPEN)) {
            throw new PeriodNotStewardException();
        }
        if (!period.isClosed()) {
            throw new PeriodRefusedException("not closed");
        }
        if (period.isLocked()) {
            throw new PeriodRefusedException("locked");
        }
        period.reopen();
        periods.flush();
        audit.change("PERIOD_REOPENED", sheetId, user, "period=" + period.getName());
        return period;
    }

    /** Lock the period, so it cannot be reopened; refused when the user is not a steward or it is already locked. */
    @Transactional
    public Period lock(String sheetId, String user, long periodId) {
        Period period = periods.lockByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PeriodNotStewardException();
        }
        if (period.isLocked()) {
            throw new PeriodRefusedException("already locked");
        }
        period.setLocked(true);
        periods.flush();
        audit.change("PERIOD_LOCKED", sheetId, user, "period=" + period.getName());
        return period;
    }

    /** Unlock the period; refused when the user is not a steward or it is not locked. */
    @Transactional
    public Period unlock(String sheetId, String user, long periodId) {
        Period period = periods.lockByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PeriodNotStewardException();
        }
        if (!period.isLocked()) {
            throw new PeriodRefusedException("not locked");
        }
        period.setLocked(false);
        periods.flush();
        audit.change("PERIOD_UNLOCKED", sheetId, user, "period=" + period.getName());
        return period;
    }

    /** Each person's due-total for the period now, by person id, for everyone on the sheet: the sum of
     * what is due on their claims whose due-by falls in it that counted by the status each had at the end of the
     * period's last day (see {@link #statusAt}), as each shows it now, late charges included (a
     * claim with no figure adds nothing, nor does an OPEN claim deferred on the sheet's today or an unbound OPEN claim dated within one of its person's holds, while the claim is still OPEN or PENDING now). */
    private Map<Long, BigDecimal> personTotalsOf(Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(period.getSheetId()).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (Long id : byId.keySet()) totals.put(id, BigDecimal.ZERO.setScale(2));
        LocalDate today = sheets.findById(period.getSheetId()).map(Sheet::getToday).orElse(null);
        List<Claim> dated = claims.findBySheetIdOrderByListedDescIdDesc(period.getSheetId()).stream()
                .filter(claim -> period.overlaps(claim.getDueBy(), claim.getDueBy())).toList();
        Map<Long, List<ClaimLog>> logsOf = dated.isEmpty() ? Map.of()
                : logs.findByClaimIdInOrderByIdAsc(dated.stream().map(Claim::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ClaimLog::getClaimId));
        for (Claim claim : dated) {
            Person person = byId.get(claim.getPersonId());
            ClaimStatus status = statusAt(logsOf.getOrDefault(claim.getId(), List.of()), period.getTo());
            if (person == null || status == null) continue;
            boolean counts = claim.getStatus().isOpen() ? RollupService.countsAs(claim, status, person, today) : status.counts();
            if (!counts) continue;
            BigDecimal figure = rollup.shownOf(claim, person);
            if (figure != null) totals.merge(person.getId(), figure, BigDecimal::add);
        }
        return totals;
    }

    /** Each person's carried figure into the period now, by person id, for everyone on the sheet: the sum
     * of what is due on their claims whose due-by falls in a period of the sheet ending before this one
     * starts and that were still OPEN or PENDING at the end of this one's last day (see {@link #statusAt}),
     * and their opening balance for it,
     * as each shows it now (a claim with no figure adds nothing, nor does one that would not count then,
     * as {@link #personTotalsOf} counts). */
    private Map<Long, BigDecimal> personCarriedOf(Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(period.getSheetId()).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, BigDecimal> carried = new HashMap<>();
        for (Long id : byId.keySet()) carried.put(id, BigDecimal.ZERO.setScale(2));
        period.getPersonOpenings().forEach((id, opening) -> {
            if (byId.containsKey(id)) carried.merge(id, opening, BigDecimal::add);
        });
        LocalDate today = sheets.findById(period.getSheetId()).map(Sheet::getToday).orElse(null);
        List<Period> all = periods.findBySheetIdOrderByFromAsc(period.getSheetId());
        if (all.stream().noneMatch(p -> p.getTo().isBefore(period.getFrom()))) return carried;
        List<Claim> before = claims.findBySheetIdOrderByListedDescIdDesc(period.getSheetId()).stream()
                .filter(claim -> claim.getDueBy() != null)
                .filter(claim -> all.stream().filter(p -> p.overlaps(claim.getDueBy(), claim.getDueBy())).findFirst()
                        .map(own -> own.getTo().isBefore(period.getFrom())).orElse(false))
                .toList();
        Map<Long, List<ClaimLog>> logsOf = before.isEmpty() ? Map.of()
                : logs.findByClaimIdInOrderByIdAsc(before.stream().map(Claim::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ClaimLog::getClaimId));
        for (Claim claim : before) {
            Person person = byId.get(claim.getPersonId());
            ClaimStatus status = statusAt(logsOf.getOrDefault(claim.getId(), List.of()), period.getTo());
            if (person == null || status == null || !status.isOpen()) continue;
            boolean counts = claim.getStatus().isOpen() ? RollupService.countsAs(claim, status, person, today) : status.counts();
            if (!counts) continue;
            BigDecimal figure = rollup.shownOf(claim, person);
            if (figure != null) carried.merge(person.getId(), figure, BigDecimal::add);
        }
        return carried;
    }

    /** The status a claim had at the end of {@code day}, by its status log, oldest first, whose lines
     * are dated with the sheet's today when each change was made; null when it was added after. */
    private static ClaimStatus statusAt(List<ClaimLog> log, LocalDate day) {
        ClaimStatus status = null;
        for (ClaimLog line : log) {
            if (line.getDate().isAfter(day)) break;
            status = line.getStatus();
        }
        return status;
    }
}
