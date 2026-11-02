package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
import net.officefloor.hq.app.pool.PoolService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's periods: each named uniquely on the sheet, from and to a date inclusive, none
 * overlapping. Closing a period fixes, at that moment, each person's due-total for it (the figures
 * as shown then of their claims whose due-by falls in it that counted by the status they had at the
 * end of its last day) and carried figure (the same of their claims due in an earlier period and still
 * open at the end of its last day), and its own due-total, the sum of their due-totals. Soft-closing fixes them the same
 * way while claims due inside it can still change; a soft-closed period can then be closed (fixing
 * them afresh). Closing or soft-closing also fixes every pool member's cut for it as it stands at that
 * moment. A steward can reopen a closed or soft-closed period, making its figures live again.
 * A steward can lock a period, and unlock it; a locked period cannot be reopened.
 */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService claimService;
    private final SettingService settings;
    private final PoolService pools;
    private final RolloverRepository rollovers;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, ClaimRepository claims,
            PersonRepository people, ClaimService claimService, SettingService settings, PoolService pools,
            RolloverRepository rollovers, Audit audit) {
        this.rollovers = rollovers;
        this.pools = pools;
        this.settings = settings;
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.claimService = claimService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to, Integer cap) {
        if (to.isBefore(from)) throw new PeriodDatesException();
        // Lock the sheet so two users adding periods at once cannot overlap or share a name.
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (periods.existsBySheetIdAndName(sheetId, name)) throw new DuplicatePeriodNameException();
        if (periods.findBySheetIdOrderByFromDateAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodOverlapException();
        }
        Period saved = periods.saveAndFlush(new Period(sheetId, name, from, to, cap));
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user.strip()
                + " period=" + saved.getName() + " from=" + saved.getFromDate() + " to=" + saved.getToDate()
                + (saved.getCap() == null ? "" : " cap=" + saved.getCap()));
        return saved;
    }

    /**
     * Make one period per calendar month from the from-date's month to the to-date's month, each from
     * the first to the last day of its month and named yyyy-mm. A month whose name is already a period,
     * or whose days overlap one on the sheet, is left out; the others are still made. A blank date
     * takes the sheet's today. For a viewer, or on an archived sheet, every month is left out.
     */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate fromOrNull, LocalDate toOrNull) {
        // Lock the sheet so two users adding periods at once cannot overlap or share a name.
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        LocalDate today = sheet.getToday();
        LocalDate from = fromOrNull == null ? today : fromOrNull;
        LocalDate to = toOrNull == null ? today : toOrNull;
        if (to.isBefore(from)) throw new PeriodDatesException();
        if (sheet.isArchived() || settings.roleOf(sheetId, user) == Role.VIEWER) return List.of();
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromDateAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (existing.stream().anyMatch(p -> p.getName().equals(name) || p.overlaps(first, last))) continue;
            Period saved = periods.saveAndFlush(new Period(sheetId, name, first, last));
            existing.add(saved);
            made.add(saved);
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user.strip()
                    + " period=" + saved.getName() + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        }
        return made;
    }

    /**
     * Make the twelve monthly periods of the fiscal year named FY&lt;year&gt;, the one whose first day (the
     * first of the sheet's fiscal start month) falls in that calendar year, exactly as {@link #generate} does.
     */
    @Transactional
    public List<Period> generateFiscalYear(String sheetId, String user, int year) {
        YearMonth first = YearMonth.of(year, settings.fiscalStartMonth(sheetId));
        return generate(sheetId, user, first.atDay(1), first.plusMonths(11).atEndOfMonth());
    }

    /**
     * A steward rolls the fiscal year FY&lt;year&gt; over: the next fiscal year's twelve periods are made as
     * {@link #generateFiscalYear} makes them, and each person's open figure at the end of the old year's last
     * period (the figures of their claims that count then, due in a period of the old year and OPEN or PENDING
     * at that period's end) is added to their opening balance for the new year's first period. A year with no
     * periods on the sheet is refused. It is kept as the sheet's last rollover, replacing any before it; rolling
     * over again the year the last rollover carried over (not undone) carries no opening balances a second time.
     * Every period of the old year still open (neither closed nor soft-closed) is then soft-closed, in start-date
     * order, as {@link #softClose} would.
     */
    @Transactional
    public List<Period> rollover(String sheetId, String user, int year) {
        // Lock the sheet so no claim or period changes while the year is carried over.
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new PeriodNotPermittedException();
        YearMonth start = YearMonth.of(year, settings.fiscalStartMonth(sheetId));
        LocalDate oldFirst = start.atDay(1);
        LocalDate oldLast = start.plusMonths(11).atEndOfMonth();
        List<Period> old = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> !p.getFromDate().isBefore(oldFirst) && !p.getFromDate().isAfter(oldLast))
                .toList();
        if (old.isEmpty()) throw new RolloverRefusedException();
        Period last = old.stream().max(Comparator.comparing(Period::getToDate)).orElseThrow();
        LocalDate end = last.getToDate();
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, Person> byId = sheetPeople.stream().collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, BigDecimal> open = new LinkedHashMap<>();
        for (Person person : sheetPeople) open.put(person.getId(), BigDecimal.ZERO.setScale(2));
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || old.stream().noneMatch(p -> p.contains(claim.getDueBy()))) continue;
            ClaimStatus status = claim.statusAt(end);
            if (status == null || !status.isOpen() || !person.countsAt(claim, end, today)) continue;
            BigDecimal figure = claimService.chargedFigureOf(sheetId, person, claim);
            if (figure != null) open.merge(person.getId(), figure, BigDecimal::add);
        }
        List<Period> made = generateFiscalYear(sheetId, user, year + 1);
        LocalDate newFirst = start.plusMonths(12).atDay(1);
        LocalDate newLast = start.plusMonths(23).atEndOfMonth();
        Period first = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> !p.getFromDate().isBefore(newFirst) && !p.getFromDate().isAfter(newLast))
                .findFirst().orElse(null);
        Map<Long, BigDecimal> added = new LinkedHashMap<>();
        boolean carried = rollovers.findById(sheetId).filter(r -> r.getFiscalYear() == year).isPresent();
        if (first != null && !carried) {
            for (Person person : sheetPeople) {
                BigDecimal amount = open.get(person.getId());
                if (amount.signum() <= 0) continue;
                first.addOpening(person.getId(), amount);
                added.put(person.getId(), amount);
                audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user.strip()
                        + " name=" + person.getName() + " period=" + first.getName()
                        + " amount=" + amount.setScale(2).toPlainString());
            }
            periods.saveAndFlush(first);
        }
        rollovers.findById(sheetId).ifPresent(before -> {
            rollovers.delete(before);
            rollovers.flush();
        });
        rollovers.saveAndFlush(new Rollover(sheetId, year, user.strip(), today, first == null ? null : first.getId(),
                made.stream().map(Period::getId).toList(), added));
        audit.recordOnCommit("ROLLOVER sheet=" + sheetId + " by=" + user.strip()
                + " fy=FY" + year + " periods=" + made.size());
        // Soft-close, as a soft close would, every old-year period still open, in start-date order.
        for (Period period : old) {
            if (!period.isClosed() && !period.isSoftClosed()) fix(sheetId, user, period, today, false, true);
        }
        return made;
    }

    /**
     * The steward who made the sheet's last rollover undoes it on the same sheet-today: the periods it generated
     * are removed and the opening balances it added are taken back. Refused when there is none to undo, on a
     * later day, for anyone else, or when a claim (whatever its status) is dated or due in a period it generated.
     */
    @Transactional
    public void undoRollover(String sheetId, String user) {
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new PeriodNotPermittedException();
        Rollover last = rollovers.findById(sheetId).orElseThrow(RolloverUndoRefusedException::new);
        if (!last.getMadeBy().equals(user.strip()) || !last.getMadeOn().equals(today)) {
            throw new RolloverUndoRefusedException();
        }
        List<Period> generated = periods.findAllById(last.getPeriodIds()).stream()
                .filter(p -> p.getSheetId().equals(sheetId))
                .toList();
        boolean claimed = claims.findBySheetIdOrderByIdDesc(sheetId).stream().anyMatch(c -> generated.stream()
                .anyMatch(p -> (c.getDate() != null && p.contains(c.getDate()))
                        || (c.getDueBy() != null && p.contains(c.getDueBy()))));
        if (claimed) throw new RolloverUndoRefusedException();
        Long openingId = last.getOpeningPeriodId();
        if (openingId != null && generated.stream().noneMatch(p -> p.getId().equals(openingId))) {
            periods.findByIdAndSheetId(openingId, sheetId).ifPresent(period -> {
                last.getOpenings().forEach(period::takeOpening);
                periods.saveAndFlush(period);
            });
        }
        periods.deleteAll(generated);
        periods.flush();
        rollovers.delete(last);
        rollovers.flush();
        audit.recordOnCommit("ROLLOVER_UNDONE sheet=" + sheetId + " by=" + user.strip()
                + " fy=FY" + last.getFiscalYear());
    }

    /**
     * Make the four calendar quarters of the year as periods named &lt;yyyy&gt;-Q1 to &lt;yyyy&gt;-Q4, each from
     * the first day of its first month to the last day of its third, as the period form would add it. A
     * quarter whose name is already a period, or whose days overlap one on the sheet, is left out; the
     * others are still made.
     */
    @Transactional
    public List<Period> generateQuarters(String sheetId, String user, int year) {
        // Lock the sheet so two users adding periods at once cannot overlap or share a name.
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromDateAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (int quarter = 1; quarter <= 4; quarter++) {
            String name = year + "-Q" + quarter;
            LocalDate first = YearMonth.of(year, quarter * 3 - 2).atDay(1);
            LocalDate last = YearMonth.of(year, quarter * 3).atEndOfMonth();
            if (existing.stream().anyMatch(p -> p.getName().equals(name) || p.overlaps(first, last))) continue;
            Period saved = periods.saveAndFlush(new Period(sheetId, name, first, last));
            existing.add(saved);
            made.add(saved);
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user.strip()
                    + " period=" + saved.getName() + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        }
        return made;
    }

    @Transactional
    public Period close(String sheetId, String user, Long id) {
        // Lock the sheet so no claim in the period changes while its due-total is fixed.
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        Period period = periods.findByIdAndSheetId(id, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (!settings.mayDo(sheetId, user, "close")) throw new PeriodNotPermittedException();
        if (period.isClosed()) throw new PeriodClosedException();
        return fix(sheetId, user, period, today, false, false);
    }

    @Transactional
    public Period softClose(String sheetId, String user, Long id) {
        // Lock the sheet so no claim in the period changes while its due-total is fixed.
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        Period period = periods.findByIdAndSheetId(id, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (!settings.mayDo(sheetId, user, "close")) throw new PeriodNotPermittedException();
        if (period.isClosed() || period.isSoftClosed()) throw new PeriodClosedException();
        return fix(sheetId, user, period, today, false, true);
    }

    /**
     * Close, as a close by hand would, every open period (not a soft-closed one) whose to-date plus the grace days is before the
     * sheet's today, in order of their to-dates, each audited with auto=yes. The caller holds the sheet's
     * lock, with its new today already set.
     */
    @Transactional
    public void autoClose(String sheetId, String user, LocalDate today, int graceDays) {
        List<Period> due = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> !p.isClosed() && !p.isSoftClosed() && p.getToDate().plusDays(graceDays).isBefore(today))
                .sorted(Comparator.comparing(Period::getToDate))
                .toList();
        for (Period period : due) fix(sheetId, user, period, today, true, false);
    }

    /** Close or soft-close the period, fixing each person's due-total for it and its own, and audit it. */
    private Period fix(String sheetId, String user, Period period, LocalDate today, boolean auto, boolean soft) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        // Every person on the sheet gets a figure, 0.00 when none of their claims falls in it.
        Map<Long, BigDecimal> personDues = new LinkedHashMap<>();
        for (Long personId : byId.keySet()) personDues.put(personId, BigDecimal.ZERO.setScale(2));
        Map<Long, BigDecimal> personCarried = new LinkedHashMap<>(personDues);
        // their opening balances for it are part of what they carry into it
        period.getOpenings().forEach((personId, amount) -> {
            if (personCarried.containsKey(personId)) personCarried.merge(personId, amount, BigDecimal::add);
        });
        List<Period> all = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !person.countsAt(claim, period.getToDate(), today)) continue;
            if (period.contains(claim.getDueBy())) {
                BigDecimal figure = claimService.chargedFigureOf(sheetId, person, claim);
                if (figure != null) personDues.merge(person.getId(), figure, BigDecimal::add);
            } else if (carriedInto(period, claim, all)) {
                BigDecimal figure = claimService.chargedFigureOf(sheetId, person, claim);
                if (figure != null) personCarried.merge(person.getId(), figure, BigDecimal::add);
            }
        }
        // every pool member's cut as it stands now
        List<PoolCutLine> cuts = new ArrayList<>();
        for (PoolService.PoolLine pool : pools.pools(sheetId)) {
            for (PoolService.Member m : pool.members()) {
                cuts.add(new PoolCutLine(pool.id(), pool.crewId(), m.personId(), m.poolId(), m.cut()));
            }
        }
        period.fixPoolCuts(cuts);
        if (soft) period.softClose(personDues, personCarried);
        else period.close(personDues, personCarried);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit((soft ? "PERIOD_SOFT_CLOSED" : "PERIOD_CLOSED") + " sheet=" + sheetId + " by=" + user.strip()
                + " period=" + saved.getName() + " total=" + saved.getClosedDueTotal().setScale(2).toPlainString()
                + (auto ? " auto=yes" : ""));
        return saved;
    }

    /**
     * Whether the claim is carried into the period: due in a period ending before it starts, and still
     * open (OPEN or PENDING) at the end of its last day, by the status log.
     */
    private static boolean carriedInto(Period period, Claim claim, List<Period> all) {
        Period own = all.stream().filter(p -> p.contains(claim.getDueBy())).findFirst().orElse(null);
        if (own == null || !own.getToDate().isBefore(period.getFromDate())) return false;
        ClaimStatus status = claim.statusAt(period.getToDate());
        return status != null && status.isOpen();
    }

    @Transactional
    public Period reopen(String sheetId, String user, Long id) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Period period = periods.findByIdAndSheetId(id, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (!settings.mayDo(sheetId, user, "reopen")) throw new PeriodNotPermittedException();
        if (!period.isClosed() && !period.isSoftClosed()) throw new PeriodOpenException();
        if (period.isLocked()) throw new PeriodLockedException();
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user.strip()
                + " period=" + saved.getName());
        return saved;
    }

    /** A steward locks the period, or unlocks it; locking a locked one or unlocking an unlocked one is refused. */
    @Transactional
    public Period lock(String sheetId, String user, Long id, boolean lock) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Period period = periods.findByIdAndSheetId(id, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new PeriodNotPermittedException();
        if (lock && period.isLocked()) throw new PeriodLockedException();
        if (!lock && !period.isLocked()) throw new PeriodNotLockedException();
        period.setLocked(lock);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit((lock ? "PERIOD_LOCKED" : "PERIOD_UNLOCKED") + " sheet=" + sheetId + " by=" + user.strip()
                + " period=" + saved.getName());
        return saved;
    }
}
