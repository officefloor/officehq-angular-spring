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
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonHold;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.setting.Permission;
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
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, ClaimRepository claims,
            ClaimService claimService, PersonRepository people, PersonService personService, SettingService settings,
            Audit audit) {
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

    /** Add a period from and to a date, inclusive. Refused when another period on the sheet has the
     *  name, the from-date is after the to-date, or it shares a day with another period. The sheet
     *  is held locked so periods added at the same moment are checked against each other. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.lock(sheetId);
        if (from.isAfter(to)) {
            throw new PeriodRefusedException("The period's from-date is after its to-date");
        }
        if (periods.nameInUse(sheetId, name)) {
            throw new PeriodRefusedException("The name " + name + " is already in use on the sheet");
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefusedException("The period overlaps another on the sheet");
        }
        Period saved = periods.saveAndFlush(new Period(sheetId, name, from, to));
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + name
                + " from=" + from + " to=" + to);
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
            Period saved = periods.saveAndFlush(new Period(sheetId, name, first, last));
            existing.add(saved);
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + name
                    + " from=" + first + " to=" + last);
            made++;
        }
        return made;
    }

    /** Close a period, fixing each person's due-total for it, for everyone on the sheet, and its own
     *  due-total, their sum: the figures shown on the claims dated in it that counted at the end of its last day (by the
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

    /** Close, as a close by hand would, every open period whose to-date plus the sheet's grace days is
     *  before the sheet's today, in order of their to-dates, each recorded with auto=yes; nothing when
     *  the grace days are blank (off) or the user's role may not close. Made when the sheet's today is
     *  changed, after it is set. */
    @Transactional
    public void autoClose(String sheetId, String user) {
        sheets.lock(sheetId);
        Integer grace = settings.graceDays(sheetId);
        if (grace == null || !settings.allows(sheetId, user, Permission.CLOSE)) {
            return;
        }
        LocalDate today = sheets.today(sheetId);
        periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> !p.isClosed() && p.getTo().plusDays(grace).isBefore(today))
                .sorted(Comparator.comparing(Period::getTo))
                .forEach(p -> closeFixing(sheetId, user, p, " auto=yes"));
    }

    private Period closeFixing(String sheetId, String user, Period period, String suffix) {
        String name = period.getName();
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
                    || !claim.countsAsOf(atEnd.get(claim.getId()), holds.getOrDefault(person.getId(), List.of()), today)
                    || !period.contains(claim.getDate())) {
                continue;
            }
            BigDecimal due = claimService.dueOf(sheetId, person, claim);
            if (due != null) {
                personTotals.merge(person.getId(), due, BigDecimal::add);
            }
        }
        BigDecimal total = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        period.close(total, personTotals);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + name
                + " total=" + total.toPlainString() + suffix);
        return saved;
    }

    /** Reopen a closed period, making its due-total live again. Only a role the sheet's
     *  permission matrix allows may (by the standing rule a steward); a period that is not closed is refused. */
    @Transactional
    public Period reopen(String sheetId, String user, String name) {
        sheets.lock(sheetId);
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new NoSuchElementException("No period " + name + " on sheet " + sheetId));
        if (!settings.allows(sheetId, user, Permission.REOPEN)) {
            throw new PeriodRefusedException("Your role may not reopen the period " + name);
        }
        if (!period.isClosed()) {
            throw new PeriodRefusedException("The period " + name + " is not closed");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + name);
        return saved;
    }
}
