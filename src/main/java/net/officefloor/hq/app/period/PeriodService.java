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
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import org.springframework.context.event.EventListener;
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
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService claimService;
    private final SettingsService settings;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, ClaimRepository claims,
            PersonRepository people, ClaimService claimService, SettingsService settings, Audit audit) {
        this.settings = settings;
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.claimService = claimService;
        this.audit = audit;
    }

    /** The sheet's periods, by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /**
     * Adds a period; refused when its to-date is before its from-date, its name is in use on the sheet or its dates
     * overlap another period's. A cap, when given, is the most OPEN or PENDING claims a person may have in it.
     */
    @Transactional
    public Period add(String by, String sheetId, String name, LocalDate from, LocalDate to, Integer cap) {
        // the sheet stays locked until the transaction ends, so two periods added at once are checked in turn
        sheets.lock(sheetId);
        String trimmed = name.strip();
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts.");
        }
        if (periods.existsBySheetIdAndName(sheetId, trimmed)) {
            throw nameInUse();
        }
        if (periods.existsBySheetIdAndFromLessThanEqualAndToGreaterThanEqual(sheetId, to, from)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another.");
        }
        Period added;
        try {
            added = periods.saveAndFlush(new Period(sheetId, trimmed, from, to, cap));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + by + " period=" + added.getName()
                + " from=" + added.getFrom() + " to=" + added.getTo()
                + (added.getCap() != null ? " cap=" + added.getCap() : ""));
        return added;
    }

    /**
     * Adds one period per calendar month from the from-date's month to the to-date's month, each from the first to the
     * last day of its month and named yyyy-mm, by month. A month whose name is in use on the sheet or whose days
     * overlap a period is left out; the others are still made. Refused when the to-date is before the from-date.
     */
    @Transactional
    public List<Period> generate(String by, String sheetId, LocalDate from, LocalDate to) {
        // the sheet stays locked until the transaction ends, so periods added at once are checked in turn
        sheets.lock(sheetId);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date cannot be before the from-date.");
        }
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (periods.existsBySheetIdAndName(sheetId, name)
                    || periods.existsBySheetIdAndFromLessThanEqualAndToGreaterThanEqual(sheetId, last, first)) {
                continue;
            }
            Period added = periods.saveAndFlush(new Period(sheetId, name, first, last));
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + by + " period=" + added.getName()
                    + " from=" + added.getFrom() + " to=" + added.getTo());
            made.add(added);
        }
        return made;
    }

    /**
     * Adds the four calendar quarters of the year, named yyyy-Q1 to yyyy-Q4, each from the first day of its first month
     * to the last day of its third, by quarter. A quarter whose name is in use on the sheet or whose days overlap a
     * period is left out; the others are still made.
     */
    @Transactional
    public List<Period> generateQuarters(String by, String sheetId, int year) {
        // the sheet stays locked until the transaction ends, so periods added at once are checked in turn
        sheets.lock(sheetId);
        List<Period> made = new ArrayList<>();
        for (int quarter = 1; quarter <= 4; quarter++) {
            String name = String.format("%04d-Q%d", year, quarter);
            LocalDate first = YearMonth.of(year, quarter * 3 - 2).atDay(1);
            LocalDate last = YearMonth.of(year, quarter * 3).atEndOfMonth();
            if (periods.existsBySheetIdAndName(sheetId, name)
                    || periods.existsBySheetIdAndFromLessThanEqualAndToGreaterThanEqual(sheetId, last, first)) {
                continue;
            }
            Period added = periods.saveAndFlush(new Period(sheetId, name, first, last));
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + by + " period=" + added.getName()
                    + " from=" + added.getFrom() + " to=" + added.getTo());
            made.add(added);
        }
        return made;
    }

    /**
     * Adds the twelve monthly periods of the fiscal year FY<year>, the one starting on the first of the sheet's fiscal
     * start month in that year, exactly as {@link #generate} does for its first through its last day.
     */
    @Transactional
    public List<Period> generateFiscalYear(String by, String sheetId, int year) {
        LocalDate first = YearMonth.of(year, sheets.lock(sheetId).getFiscalStartMonth()).atDay(1);
        return generate(by, sheetId, first, first.plusYears(1).minusDays(1));
    }

    /**
     * Rolls the fiscal year FY<year> over: generates FY<year + 1>'s twelve monthly periods exactly as
     * {@link #generateFiscalYear} does, then adds each person's open figure at the end of FY<year>'s last period (the
     * figures of their claims that count, belonging to a period of FY<year>, still open or pending at the end of its last
     * day, skipping n/a) to their opening balance for FY<year + 1>'s first period. Refused unless the user is a steward,
     * and when FY<year> has no periods. Answers the periods made.
     */
    @Transactional
    public List<Period> rollover(String by, String sheetId, int year) {
        Sheet sheet = sheets.lock(sheetId);
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can roll the year over.");
        }
        LocalDate today = sheet.getToday();
        List<Period> old = periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> sheet.fiscalYearOf(p.getFrom()) == year).toList();
        if (old.isEmpty()) {
            throw refused("FY" + year + " has no periods.");
        }
        LocalDate end = old.stream().map(Period::getTo).max(LocalDate::compareTo).orElseThrow();
        // the open figures are read before the new year's periods exist, so none of its claims belong to them
        List<Person> everyone = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, Person> byId = everyone.stream().collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, BigDecimal> open = new HashMap<>();
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(c.getPersonId());
            if (person == null || c.getDueBy() == null || old.stream().noneMatch(p -> p.contains(c.getDueBy()))
                    || !person.countsAtEndOf(c, end, today) || !c.statusAtEndOf(end).isOpen()) {
                continue;
            }
            BigDecimal figure = claimService.due(sheetId, person, c);
            if (figure != null) {
                open.merge(person.getId(), figure, BigDecimal::add);
            }
        }
        List<Period> made = generateFiscalYear(by, sheetId, year + 1);
        LocalDate first = YearMonth.of(year + 1, sheet.getFiscalStartMonth()).atDay(1);
        Period opening = periods.findBySheetIdOrderByFromAsc(sheetId).stream().filter(p -> p.contains(first))
                .findFirst().orElse(null);
        if (opening != null) {
            for (Person person : everyone) {
                BigDecimal amount = open.getOrDefault(person.getId(), BigDecimal.ZERO).setScale(2);
                if (amount.signum() <= 0) {
                    continue;
                }
                opening.addOpening(person.getId(), amount);
                audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + by + " name=" + person.getName()
                        + " period=" + opening.getName() + " amount=" + amount.toPlainString());
            }
            periods.saveAndFlush(opening);
        }
        audit.recordOnCommit("ROLLOVER sheet=" + sheetId + " by=" + by + " fy=FY" + year + " periods=" + made.size());
        return made;
    }

    /**
     * Closes the period, fixing each person's due-total for it now (the figures of their claims due by a date in it that
     * counted at the end of its last day, skipping n/a) and its own, the sum of those.
     * Refused unless the user's role may close, and when it is already closed.
     */
    @Transactional
    public Period close(String by, String sheetId, Long id) {
        // the sheet stays locked until the transaction ends, so no claim in the period changes while it closes
        LocalDate today = sheets.lock(sheetId).getToday();
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        settings.refuseUnlessMay(sheetId, by, "close");
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed.");
        }
        return close(by, sheetId, period, today, "");
    }

    /**
     * Soft-closes the period, fixing its figures as a close does while claims due by a date in it can still change. Refused
     * unless the user's role may close, and when it is already closed or soft-closed.
     */
    @Transactional
    public Period softClose(String by, String sheetId, Long id) {
        LocalDate today = sheets.lock(sheetId).getToday();
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        settings.refuseUnlessMay(sheetId, by, "close");
        if (period.isClosed() || period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed.");
        }
        Map<Long, BigDecimal> personTotals = personTotals(sheetId, period, today);
        BigDecimal total = sum(personTotals);
        period.softClose(total, personTotals, personCarried(sheetId, period, today));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + by + " period=" + saved.getName()
                + " total=" + total.toPlainString());
        return saved;
    }

    /**
     * Once the sheet's today is changed while it has grace days, closes every open period (not soft-closed) whose to-date plus them is
     * before the new today, by to-date, each as a close by hand would.
     */
    @EventListener
    @Transactional
    public void autoClose(TodayChanged change) {
        Sheet sheet = change.sheet();
        Integer graceDays = sheet.getGraceDays();
        if (graceDays == null) {
            return;
        }
        LocalDate today = sheet.getToday();
        for (Period period : periods.findBySheetIdAndClosedFalseAndToBeforeOrderByToAsc(sheet.getId(),
                today.minusDays(graceDays))) {
            // a soft-closed period keeps the figures it fixed
            if (period.isSoftClosed()) {
                continue;
            }
            close(change.by(), sheet.getId(), period, today, " auto=yes");
        }
    }

    private Period close(String by, String sheetId, Period period, LocalDate today, String auditSuffix) {
        Map<Long, BigDecimal> personTotals = personTotals(sheetId, period, today);
        BigDecimal total = sum(personTotals);
        period.close(total, personTotals, personCarried(sheetId, period, today));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + by + " period=" + saved.getName()
                + " total=" + total.toPlainString() + auditSuffix);
        return saved;
    }

    private static BigDecimal sum(Map<Long, BigDecimal> personTotals) {
        return personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /**
     * Each person's due-total for the period now: the figures of their claims due by a date in it that counted at the end of
     * its last day, skipping n/a; 0.00 for a person with none.
     */
    private Map<Long, BigDecimal> personTotals(String sheetId, Period period, LocalDate today) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        // every person on the sheet now gets a figure, 0.00 when none of their counted claims is due by a date in it
        Map<Long, BigDecimal> personTotals = new HashMap<>();
        for (Long personId : byId.keySet()) {
            personTotals.put(personId, BigDecimal.ZERO.setScale(2));
        }
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(c.getPersonId());
            if (person == null || !person.countsAtEndOf(c, period.getTo(), today) || !period.contains(c.getDueBy())) {
                continue;
            }
            BigDecimal figure = claimService.due(sheetId, person, c);
            if (figure != null) {
                personTotals.merge(person.getId(), figure, BigDecimal::add);
            }
        }
        return personTotals;
    }

    /**
     * Each person's carried figure for the period now: the figures of their claims belonging to a period of the sheet
     * that ends before this one starts and still open or pending at the end of its last day, skipping n/a, plus their
     * opening balance for it; 0.00 for a person with none.
     */
    private Map<Long, BigDecimal> personCarried(String sheetId, Period period, LocalDate today) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        List<Period> earlier = periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> p.getTo().isBefore(period.getFrom())).toList();
        Map<Long, BigDecimal> carried = new HashMap<>();
        for (Long personId : byId.keySet()) {
            carried.put(personId, period.getPersonOpenings().getOrDefault(personId, BigDecimal.ZERO).setScale(2));
        }
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(c.getPersonId());
            if (person == null || c.getDueBy() == null || earlier.stream().noneMatch(p -> p.contains(c.getDueBy()))
                    || !person.countsAtEndOf(c, period.getTo(), today)
                    || !c.statusAtEndOf(period.getTo()).isOpen()) {
                continue;
            }
            BigDecimal figure = claimService.due(sheetId, person, c);
            if (figure != null) {
                carried.merge(person.getId(), figure, BigDecimal::add);
            }
        }
        return carried;
    }

    /** A pasted line of opening balances read: the person, the period and the amount. */
    private record OpeningLine(Person person, Period period, BigDecimal amount) {
    }

    /**
     * Reads every pasted line of name,period,amount first (a person on the sheet by name or alias, a period on the sheet
     * by name, an amount of two decimals at most, 0.00 or more), then adds each line's amount to the person's opening
     * balance for the period, in order. One change: any line refused refuses them all, and nothing is stored or audited.
     * Answers how many lines went through.
     */
    @Transactional
    public int importOpenings(String by, String sheetId, String text) {
        sheets.lock(sheetId);
        Map<String, Period> byName = periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .collect(Collectors.toMap(Period::getName, Function.identity()));
        List<OpeningLine> lines = new ArrayList<>();
        int n = 0;
        for (String raw : text.split("\\r?\\n")) {
            n++;
            if (raw.isBlank()) {
                continue;
            }
            String[] f = raw.split(",", -1);
            if (f.length != 3) {
                throw refused("Line " + n + " is not name,period,amount.");
            }
            String name = f[0].strip();
            Person person = name.isEmpty() ? null : people.findBySheetIdAndNameOrAlias(sheetId, name).orElse(null);
            if (person == null) {
                throw refused("Line " + n + " names no one on the sheet.");
            }
            Period period = byName.get(f[1].strip());
            if (period == null) {
                throw refused("Line " + n + " names no period on the sheet.");
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(f[2].strip());
            } catch (NumberFormatException e) {
                throw refused("Line " + n + " has no amount.");
            }
            if (amount.scale() > 2 || amount.precision() - amount.scale() > 12) {
                throw refused("Line " + n + " has an amount with more than two decimals.");
            }
            if (amount.signum() < 0) {
                throw refused("Line " + n + " has an amount below 0.00.");
            }
            lines.add(new OpeningLine(person, period, amount.setScale(2)));
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of name,period,amount.");
        }
        for (OpeningLine l : lines) {
            l.period().addOpening(l.person().getId(), l.amount());
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + by + " name=" + l.person().getName()
                    + " period=" + l.period().getName() + " amount=" + l.amount().toPlainString());
        }
        periods.saveAllAndFlush(lines.stream().map(OpeningLine::period).distinct().toList());
        return lines.size();
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, reason);
    }

    /**
     * Reopens a closed or soft-closed period, making its due-total live again. Refused unless the user's role may reopen (a steward by
     * the standing rule), when the period is not closed, or while it is locked.
     */
    @Transactional
    public Period reopen(String by, String sheetId, Long id) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        settings.refuseUnlessMay(sheetId, by, "reopen");
        if (!period.isClosed() && !period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is not closed.");
        }
        if (period.isLocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is locked; unlock it first.");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + by + " period=" + saved.getName());
        return saved;
    }

    /**
     * Locks the period, or unlocks it; a locked period cannot be reopened. Refused unless the user is a steward, and
     * when the period is already locked (or, to unlock, not locked).
     */
    @Transactional
    public Period lock(String by, String sheetId, Long id, boolean locked) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        if (!settings.isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can lock or unlock a period.");
        }
        if (period.isLocked() == locked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    locked ? "That period is already locked." : "That period is not locked.");
        }
        period.setLocked(locked);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit((locked ? "PERIOD_LOCKED" : "PERIOD_UNLOCKED") + " sheet=" + sheetId + " by=" + by
                + " period=" + saved.getName());
        return saved;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet.");
    }
}
