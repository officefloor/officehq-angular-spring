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
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's periods: each named uniquely on the sheet, from and to a date inclusive, none
 * overlapping. Closing a period fixes, at that moment, each person's due-total for it (the figures
 * as shown then of their claims whose date falls in it that counted by the status they had at the
 * end of its last day) and its own due-total, the sum of theirs. Soft-closing fixes them the same
 * way while claims dated inside it can still change; a soft-closed period can then be closed (fixing
 * them afresh). A steward can reopen a closed or soft-closed period, making its due-total live again.
 */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService claimService;
    private final SettingService settings;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, ClaimRepository claims,
            PersonRepository people, ClaimService claimService, SettingService settings, Audit audit) {
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
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new PeriodDatesException();
        // Lock the sheet so two users adding periods at once cannot overlap or share a name.
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (periods.existsBySheetIdAndName(sheetId, name)) throw new DuplicatePeriodNameException();
        if (periods.findBySheetIdOrderByFromDateAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodOverlapException();
        }
        Period saved = periods.saveAndFlush(new Period(sheetId, name, from, to));
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user.strip()
                + " period=" + saved.getName() + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        return saved;
    }

    /**
     * Make one period per calendar month from the from-date's month to the to-date's month, each from
     * the first to the last day of its month and named yyyy-mm. A month whose name is already a period,
     * or whose days overlap one on the sheet, is left out; the others are still made. A blank date
     * takes the sheet's today.
     */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate fromOrNull, LocalDate toOrNull) {
        // Lock the sheet so two users adding periods at once cannot overlap or share a name.
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        LocalDate from = fromOrNull == null ? today : fromOrNull;
        LocalDate to = toOrNull == null ? today : toOrNull;
        if (to.isBefore(from)) throw new PeriodDatesException();
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
     * Close, as a close by hand would, every open period whose to-date plus the grace days is before the
     * sheet's today, in order of their to-dates, each audited with auto=yes. The caller holds the sheet's
     * lock, with its new today already set.
     */
    @Transactional
    public void autoClose(String sheetId, String user, LocalDate today, int graceDays) {
        List<Period> due = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> !p.isClosed() && p.getToDate().plusDays(graceDays).isBefore(today))
                .sorted(Comparator.comparing(Period::getToDate))
                .toList();
        for (Period period : due) fix(sheetId, user, period, today, true, false);
    }

    /** Close or soft-close the period, fixing each person's due-total for it and its own, and audit it. */
    private Period fix(String sheetId, String user, Period period, LocalDate today, boolean auto, boolean soft) {
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        // Every person on the sheet gets a figure, 0.00 when none of their claims falls in it.
        Map<Long, BigDecimal> personDues = new LinkedHashMap<>();
        for (Long personId : byId.keySet()) personDues.put(personId, BigDecimal.ZERO.setScale(2));
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !period.contains(claim.getDate()) || !person.countsAt(claim, period.getToDate(), today)) continue;
            BigDecimal figure = claimService.chargedFigureOf(sheetId, person, claim);
            if (figure != null) personDues.merge(person.getId(), figure, BigDecimal::add);
        }
        if (soft) period.softClose(personDues);
        else period.close(personDues);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit((soft ? "PERIOD_SOFT_CLOSED" : "PERIOD_CLOSED") + " sheet=" + sheetId + " by=" + user.strip()
                + " period=" + saved.getName() + " total=" + saved.getClosedDueTotal().setScale(2).toPlainString()
                + (auto ? " auto=yes" : ""));
        return saved;
    }

    @Transactional
    public Period reopen(String sheetId, String user, Long id) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Period period = periods.findByIdAndSheetId(id, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (!settings.mayDo(sheetId, user, "reopen")) throw new PeriodNotPermittedException();
        if (!period.isClosed() && !period.isSoftClosed()) throw new PeriodOpenException();
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user.strip()
                + " period=" + saved.getName());
        return saved;
    }
}
