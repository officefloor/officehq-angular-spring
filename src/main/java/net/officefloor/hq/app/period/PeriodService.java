package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonHold;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.setting.Permission;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The periods on a sheet: named date ranges, no two overlapping. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PersonRepository people;
    private final PersonService personService;
    private final SettingService settings;
    private final History history;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, ClaimRepository claims,
            ClaimService claimService, PersonRepository people, PersonService personService, SettingService settings,
            History history, Audit audit) {
        this.history = history;
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
        this.personService = personService;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's periods, the earliest first. */
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** The sheet's periods as they stood at the end of a day, the earliest first: those added by then,
     *  each closed, soft-closed or open as it was then. */
    public List<History.PeriodThen> listAsOf(String sheetId, LocalDate day) {
        return history.periods(sheetId, day).values().stream()
                .sorted(Comparator.comparing(History.PeriodThen::from)).toList();
    }

    /** Save a period, keeping the state it is saved in with the sheet's today. */
    private Period save(Period period) {
        Period saved = periods.saveAndFlush(period);
        history.periodSaved(saved.getId(), saved.getSheetId(), saved.getName(), saved.getFrom(), saved.getTo(),
                saved.isClosed(), saved.isSoftClosed(), saved.getClosedTotal(), saved.getPersonTotals(),
                saved.getPersonCarried());
        return saved;
    }

    /** Add a period from and to a date, inclusive, capping each person's OPEN and PENDING claims in
     *  it (null: no cap). Refused when the cap is below 1, when another period on the sheet has the
     *  name, the from-date is after the to-date, or it shares a day with another period. The sheet
     *  is held locked so periods added at the same moment are checked against each other. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to, Integer cap) {
        sheets.lock(sheetId);
        if (cap != null && cap < 1) {
            throw new PeriodRefusedException("The period's cap is not a whole number of at least 1");
        }
        if (from.isAfter(to)) {
            throw new PeriodRefusedException("The period's from-date is after its to-date");
        }
        if (periods.nameInUse(sheetId, name)) {
            throw new PeriodRefusedException("The name " + name + " is already in use on the sheet");
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefusedException("The period overlaps another on the sheet");
        }
        Period saved = save(new Period(sheetId, name, from, to, cap));
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + name
                + " from=" + from + " to=" + to + (cap != null ? " cap=" + cap : ""));
        return saved;
    }

    /** Generate one period per calendar month from the from-date's month to the to-date's month,
     *  each running from the first to the last day of its month and named yyyy-mm; a blank date is
     *  the sheet's today. A month whose name is already a period, or whose days overlap a period on
     *  the sheet, is left out and the others are still made. Refused when the to-date is before the
     *  from-date. Returns how many were made. */
    @Transactional
    public int generate(String sheetId, String user, LocalDate from, LocalDate to) {
        sheets.lock(sheetId);
        LocalDate today = sheets.today(sheetId);
        LocalDate start = from != null ? from : today;
        LocalDate end = to != null ? to : today;
        if (end.isBefore(start)) {
            throw new PeriodRefusedException("The to-date is before the from-date");
        }
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromAsc(sheetId));
        int made = 0;
        for (YearMonth month = YearMonth.from(start); !month.isAfter(YearMonth.from(end)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (periods.nameInUse(sheetId, name) || existing.stream().anyMatch(p -> p.overlaps(first, last))) {
                continue;
            }
            Period saved = save(new Period(sheetId, name, first, last));
            existing.add(saved);
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + name
                    + " from=" + first + " to=" + last);
            made++;
        }
        return made;
    }

    /** Generate the twelve monthly periods of fiscal year FY<year>, the one whose first day is the first
     *  of the sheet's fiscal year start month in that year, exactly as {@link #generate} does. Returns
     *  how many were made. */
    @Transactional
    public int generateFiscalYear(String sheetId, String user, int year) {
        LocalDate first = LocalDate.of(year, settings.fiscalStartMonth(sheetId), 1);
        return generate(sheetId, user, first, first.plusMonths(11));
    }

    /** Close a period (one soft-closed too, fixing its figures afresh), fixing each person's due-total for it, for everyone on the sheet, and its own
     *  due-total, their sum: the figures shown on the claims due by a day in it that counted at the end of its last day (by the
     *  status each had then, as its status log records it)
     *  (a claim with no figure adds nothing; an OPEN claim dated within its person's hold and not bound by a statement counts for nothing). A period already closed, or a close by a role the sheet's permission matrix does not allow, is refused. */
    @Transactional
    public Period close(String sheetId, String user, String name) {
        sheets.lock(sheetId);
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new NoSuchElementException("No period " + name + " on sheet " + sheetId));
        if (!settings.allows(sheetId, user, Permission.CLOSE)) {
            throw new PeriodRefusedException("Your role may not close the period " + name);
        }
        if (period.isClosed()) {
            throw new PeriodClosedException("The period " + name + " is already closed");
        }
        return closeFixing(sheetId, user, period, "");
    }

    /** Close, as a close by hand would, every open period (not closed or soft-closed) whose to-date plus the sheet's grace days is
     *  before the sheet's today, in order of their to-dates, each recorded with auto=yes; nothing when
     *  the grace days are blank (off). The sheet's permission matrix governs a close by hand, not this
     *  one. Made when the sheet's today is changed, after it is set. */
    @Transactional
    public void autoClose(String sheetId, String user) {
        sheets.lock(sheetId);
        Integer grace = settings.graceDays(sheetId);
        if (grace == null) {
            return;
        }
        LocalDate today = sheets.today(sheetId);
        periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> !p.isClosed() && !p.isSoftClosed() && p.getTo().plusDays(grace).isBefore(today))
                .sorted(Comparator.comparing(Period::getTo))
                .forEach(p -> closeFixing(sheetId, user, p, " auto=yes"));
    }

    /** Soft-close a period, fixing each person's due-total for it and its own due-total as a close
     *  would, while claims dated in it can still be added and changed. A period already closed or
     *  soft-closed, or a soft close by a role the sheet's permission matrix does not allow to close,
     *  is refused. */
    @Transactional
    public Period softClose(String sheetId, String user, String name) {
        sheets.lock(sheetId);
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new NoSuchElementException("No period " + name + " on sheet " + sheetId));
        if (!settings.allows(sheetId, user, Permission.CLOSE)) {
            throw new PeriodRefusedException("Your role may not close the period " + name);
        }
        if (period.isClosed() || period.isSoftClosed()) {
            throw new PeriodClosedException("The period " + name + " is already closed");
        }
        Map<Long, BigDecimal> personTotals = personTotals(sheetId, period);
        BigDecimal total = sum(personTotals);
        period.softClose(total, personTotals, personCarried(sheetId, period));
        Period saved = save(period);
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + user + " period=" + name
                + " total=" + total.toPlainString());
        return saved;
    }

    private Period closeFixing(String sheetId, String user, Period period, String suffix) {
        String name = period.getName();
        Map<Long, BigDecimal> personTotals = personTotals(sheetId, period);
        BigDecimal total = sum(personTotals);
        period.close(total, personTotals, personCarried(sheetId, period));
        Period saved = save(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + name
                + " total=" + total.toPlainString() + suffix);
        return saved;
    }

    private static BigDecimal sum(Map<Long, BigDecimal> personTotals) {
        return personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Each person's due-total for the period now, by person id, everyone on the sheet. */
    private Map<Long, BigDecimal> personTotals(String sheetId, Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<PersonHold>> holds = personService.holds(sheetId);
        LocalDate today = sheets.today(sheetId);
        Map<Long, BigDecimal> personTotals = new LinkedHashMap<>();
        byId.keySet().forEach(id -> personTotals.put(id, BigDecimal.ZERO.setScale(2)));
        Map<Long, ClaimStatus> atEnd = claimService.statusesAsOf(sheetId, period.getTo());
        for (Claim claim : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null
                    || !claim.countsAsOf(atEnd.get(claim.getId()), holds.getOrDefault(person.getId(), List.of()),
                            person.getRetiredFrom(), today)
                    || !period.contains(claim.getDueBy())) {
                continue;
            }
            BigDecimal due = claimService.dueOf(sheetId, person, claim);
            if (due != null) {
                personTotals.merge(person.getId(), due, BigDecimal::add);
            }
        }
        return personTotals;
    }

    /** Each person's carried figure for the period now, by person id, everyone on the sheet: the dues
     *  shown now on their claims whose due-by falls in a period ending before this one starts, that
     *  were still OPEN or PENDING at the end of this period's last day (by the status each had then, as
     *  its status log records it) and the live rules leave in (a claim with no figure adds nothing), and
     *  their opening balance for it. */
    private Map<Long, BigDecimal> personCarried(String sheetId, Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, List<PersonHold>> holds = personService.holds(sheetId);
        List<Period> all = periods.findBySheetIdOrderByFromAsc(sheetId);
        LocalDate today = sheets.today(sheetId);
        Map<Long, BigDecimal> carried = new LinkedHashMap<>();
        byId.keySet().forEach(id -> carried.put(id, BigDecimal.ZERO.setScale(2)));
        period.getPersonOpenings().forEach((id, amount) -> carried.computeIfPresent(id, (key, sum) -> sum.add(amount)));
        Map<Long, ClaimStatus> atEnd = claimService.statusesAsOf(sheetId, period.getTo());
        for (Claim claim : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            ClaimStatus then = atEnd.get(claim.getId());
            if (person == null || then == null || !then.isOpen()
                    || !claim.countsAsOf(then, holds.getOrDefault(person.getId(), List.of()),
                            person.getRetiredFrom(), today)
                    || all.stream().noneMatch(own -> own.contains(claim.getDueBy())
                            && own.getTo().isBefore(period.getFrom()))) {
                continue;
            }
            BigDecimal due = claimService.dueOf(sheetId, person, claim);
            if (due != null) {
                carried.merge(person.getId(), due, BigDecimal::add);
            }
        }
        return carried;
    }

    /** A pasted opening balance, read: the person and period it names and its amount. */
    private record Opening(Person person, Period period, BigDecimal amount) {
    }

    /** Add each pasted line of name,period,amount to that person's opening balance for that period, in
     *  the order of the lines: a person on the sheet by name or alias, a period on the sheet by name, and
     *  an amount of 0.00 or more with up to two decimals; blank lines are skipped. Every line is checked
     *  first: one refused line, or none at all, changes nothing. How many lines went through. */
    @Transactional
    public int importOpenings(String sheetId, String user, String text) {
        sheets.lock(sheetId);
        List<Opening> lines = new ArrayList<>();
        int at = 0;
        for (String raw : text.split("\\r?\\n")) {
            at++;
            if (!raw.isBlank()) {
                lines.add(readOpening(sheetId, at, raw));
            }
        }
        if (lines.isEmpty()) {
            throw new OpeningRefusedException("Nothing to import");
        }
        for (Opening line : lines) {
            line.period().addOpening(line.person().getId(), line.amount());
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + line.person().getName()
                    + " period=" + line.period().getName() + " amount=" + line.amount().toPlainString());
        }
        lines.stream().map(Opening::period).distinct().forEach(periods::saveAndFlush);
        return lines.size();
    }

    private Opening readOpening(String sheetId, int at, String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length != 3) {
            throw new OpeningRefusedException("Line " + at + " needs name,period,amount");
        }
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null : people.findByName(sheetId, name).orElse(null);
        if (person == null) {
            throw new OpeningRefusedException("Line " + at + ": no person " + name + " on the sheet");
        }
        Period period = periods.findBySheetIdAndName(sheetId, fields[1].trim())
                .orElseThrow(() -> new OpeningRefusedException("Line " + at + ": no period " + fields[1].trim()));
        String amount = fields[2].trim();
        if (!amount.matches("\\d{1,10}(\\.\\d{1,2})?")) {
            throw new OpeningRefusedException("Line " + at + ": the amount must be 0.00 or more, with up to two decimals");
        }
        return new Opening(person, period, new BigDecimal(amount).setScale(2));
    }

    /** Reopen a closed or soft-closed period, making its due-total live again. Only a role the sheet's
     *  permission matrix allows may (by the standing rule a steward); a period that is not closed, or is
     *  locked, is refused. */
    @Transactional
    public Period reopen(String sheetId, String user, String name) {
        sheets.lock(sheetId);
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new NoSuchElementException("No period " + name + " on sheet " + sheetId));
        if (!settings.allows(sheetId, user, Permission.REOPEN)) {
            throw new PeriodRefusedException("Your role may not reopen the period " + name);
        }
        if (!period.isClosed() && !period.isSoftClosed()) {
            throw new PeriodRefusedException("The period " + name + " is not closed");
        }
        if (period.isLocked()) {
            throw new PeriodRefusedException("The period " + name + " is locked");
        }
        period.reopen();
        Period saved = save(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + name);
        return saved;
    }

    /** A steward locks a period so it cannot be reopened. Anyone else, or a period already locked, is
     *  refused. */
    @Transactional
    public Period lock(String sheetId, String user, String name) {
        return setLocked(sheetId, user, name, true);
    }

    /** A steward unlocks a locked period. Anyone else, or a period not locked, is refused. */
    @Transactional
    public Period unlock(String sheetId, String user, String name) {
        return setLocked(sheetId, user, name, false);
    }

    private Period setLocked(String sheetId, String user, String name, boolean locked) {
        sheets.lock(sheetId);
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new NoSuchElementException("No period " + name + " on sheet " + sheetId));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PeriodRefusedException(user + " is not a steward: only a steward locks or unlocks a period");
        }
        if (period.isLocked() == locked) {
            throw new PeriodRefusedException("The period " + name + " is already " + (locked ? "locked" : "unlocked"));
        }
        period.setLocked(locked);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit((locked ? "PERIOD_LOCKED" : "PERIOD_UNLOCKED") + " sheet=" + sheetId + " by=" + user
                + " period=" + name);
        return saved;
    }
}
