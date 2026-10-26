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
     * overlap another period's.
     */
    @Transactional
    public Period add(String by, String sheetId, String name, LocalDate from, LocalDate to) {
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
            added = periods.saveAndFlush(new Period(sheetId, trimmed, from, to));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + by + " period=" + added.getName()
                + " from=" + added.getFrom() + " to=" + added.getTo());
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
     * Closes the period, fixing each person's due-total for it now (the figures of their claims dated in it that
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
     * Soft-closes the period, fixing its figures as a close does while claims dated in it can still change. Refused
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
        period.softClose(total, personTotals);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + by + " period=" + saved.getName()
                + " total=" + total.toPlainString());
        return saved;
    }

    /**
     * Once the sheet's today is changed while it has grace days, closes every open period whose to-date plus them is
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
            close(change.by(), sheet.getId(), period, today, " auto=yes");
        }
    }

    private Period close(String by, String sheetId, Period period, LocalDate today, String auditSuffix) {
        Map<Long, BigDecimal> personTotals = personTotals(sheetId, period, today);
        BigDecimal total = sum(personTotals);
        period.close(total, personTotals);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + by + " period=" + saved.getName()
                + " total=" + total.toPlainString() + auditSuffix);
        return saved;
    }

    private static BigDecimal sum(Map<Long, BigDecimal> personTotals) {
        return personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /**
     * Each person's due-total for the period now: the figures of their claims dated in it that counted at the end of
     * its last day, skipping n/a; 0.00 for a person with none.
     */
    private Map<Long, BigDecimal> personTotals(String sheetId, Period period, LocalDate today) {
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        // every person on the sheet now gets a figure, 0.00 when none of their counted claims is dated in it
        Map<Long, BigDecimal> personTotals = new HashMap<>();
        for (Long personId : byId.keySet()) {
            personTotals.put(personId, BigDecimal.ZERO.setScale(2));
        }
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(c.getPersonId());
            if (person == null || !person.countsAtEndOf(c, period.getTo(), today) || !period.contains(c.getDate())) {
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
     * Reopens a closed or soft-closed period, making its due-total live again. Refused unless the user's role may reopen (a steward by
     * the standing rule), or when the period is not closed.
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
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + by + " period=" + saved.getName());
        return saved;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet.");
    }
}
