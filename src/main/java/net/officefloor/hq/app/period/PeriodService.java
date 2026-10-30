package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The periods on a sheet, earliest first: none share a name (case ignored) or a date. A closed period
 * keeps the due-total it had when closed, and no claim dated inside it can change.
 */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final AccessService access;
    private final ApplicationEventPublisher events;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetRows,
            ClaimRepository claims, PersonRepository people, RateService rates, AccessService access,
            ApplicationEventPublisher events) {
        this.periods = periods;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.access = access;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /**
     * Add a period from {@code from} to {@code to}, both inclusive. Refused when the name is taken on the
     * sheet, the to-date is before the from-date, or it shares a date with another period. Two users adding
     * one at the same moment wait on each other, so no two overlap.
     */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        if (to.isBefore(from)) {
            throw new PeriodRefused("The to-date is before the from-date.");
        }
        if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name)) {
            throw new PeriodRefused("That period name is already in use on this sheet.");
        }
        if (list(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefused("The period overlaps another period.");
        }
        Period period = periods.saveAndFlush(new Period(sheetId, name, from, to));
        events.publishEvent(new PeriodAdded(sheetId, user, name, from, to));
        return period;
    }

    /**
     * Close a period, fixing each person's due-total for it, for every person on the sheet, and its own, their sum: the due figures of the claims that count (OPEN, PENDING and SETTLED,
     * but not an unbound OPEN claim dated within a hold of its person) dated inside it, added up per person. Refused when it is already closed. Takes turns with claim changes on the
     * sheet, so the total fixed is the one the claims had at that moment.
     */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        sheetRows.lockById(sheetId);
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        if (period.isClosed()) {
            throw new PeriodRefused("The period is already closed.");
        }
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, p -> p));
        RateTable table = rates.table(sheetId);
        Map<Long, BigDecimal> totals = new HashMap<>();
        byId.keySet().forEach(id -> totals.put(id, BigDecimal.ZERO.setScale(2)));
        claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> Dues.counts(c, byId.get(c.getPersonId())::heldOn) && period.contains(c.getDate()))
                .forEach(c -> {
                    BigDecimal due = Dues.figure(c, byId.get(c.getPersonId())::gradeOn, table);
                    if (due != null) {
                        totals.merge(c.getPersonId(), due, BigDecimal::add);
                    }
                });
        totals.replaceAll((id, t) -> t.setScale(2, RoundingMode.HALF_UP));
        period.close(totals);
        BigDecimal total = period.getClosedTotal().setScale(2, RoundingMode.HALF_UP);
        periods.flush();
        events.publishEvent(new PeriodClosed(sheetId, user, period.getName(), total));
        return period;
    }

    /**
     * Reopen a closed period: its due-total is live again, the current figures of the claims dated inside
     * it. Refused unless the user is a steward on the sheet, and when the period is not closed.
     */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        sheetRows.lockById(sheetId);
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PeriodRefused("Only a steward can reopen a period.");
        }
        if (!period.isClosed()) {
            throw new PeriodRefused("The period is not closed.");
        }
        period.reopen();
        periods.flush();
        events.publishEvent(new PeriodReopened(sheetId, user, period.getName()));
        return period;
    }

    /** The closed period whose dates contain the date, if any. */
    @Transactional(readOnly = true)
    public Optional<Period> closedOn(String sheetId, LocalDate date) {
        return list(sheetId).stream().filter(p -> p.isClosed() && p.contains(date)).findFirst();
    }

    /**
     * Refuse a change to a claim dated on any of the dates when a closed period contains it. Holds the
     * sheet's lock until the transaction ends, so a period cannot close while the change is made.
     */
    @Transactional
    public void requireOpen(String sheetId, LocalDate... dates) {
        sheetRows.lockById(sheetId);
        for (LocalDate date : dates) {
            closedOn(sheetId, date).ifPresent(p -> {
                throw new ClosedPeriodRefused(p.getName());
            });
        }
    }
}
