package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The periods on a sheet: named, each running from a day to a day, none overlapping another. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final Dues dues;
    private final RoleService roles;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, ClaimRepository claims,
            PersonRepository people, Dues dues, RoleService roles, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.dues = dues;
        this.roles = roles;
        this.audit = audit;
    }

    /** The periods on the sheet, earliest first. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /** Add a period, refused when its to-date is before its from-date, its name is in use on the
     *  sheet, or it shares a day with another period. */
    @Transactional
    public synchronized Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.load(sheetId, user);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts");
        }
        if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name)) {
            throw nameInUse();
        }
        if (periods.overlaps(sheetId, from, to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another on this sheet");
        }
        Period saved;
        try {
            saved = periods.saveAndFlush(new Period(sheetId, name, from, to));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        return saved;
    }

    /** Close a period, fixing its due-total at what it is now; refused when the permission matrix does
     *  not allow the user's role to close, or it is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        sheets.load(sheetId, user);
        if (!roles.may(sheetId, user, "close")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not close a period");
        }
        Period period = periods.lockByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed");
        }
        return closeOpen(sheetId, user, period, "");
    }

    /** Close every open period on the sheet whose to-date plus the grace days is before the day,
     *  earliest to-date first, each as a close by hand would, its record marked auto=yes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void autoClose(String sheetId, String user, int graceDays, LocalDate day) {
        for (Period period : periods.findOpenEndingBefore(sheetId, day.minusDays(graceDays))) {
            closeOpen(sheetId, user, period, " auto=yes");
        }
    }

    /** Close an open period, fixing its due-total at what it is now; the suffix ends its record. */
    private Period closeOpen(String sheetId, String user, Period period, String suffix) {
        period.close(personTotals(sheetId, period));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + saved.getClosedTotal().toPlainString() + suffix);
        return saved;
    }

    /** Reopen a closed period, making its due-total live again; refused when the permission matrix
     *  does not allow the user's role to reopen, or the period is not closed. */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        sheets.load(sheetId, user);
        if (!roles.may(sheetId, user, "reopen")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not reopen a period");
        }
        Period period = periods.lockByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (!period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is not closed");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + saved.getName());
        return saved;
    }

    /** Each person's due-total for a period, by the person's id: the due figures of their claims
     *  whose date falls in it and that count by their status at its end, skipping those with no due
     *  and those the live rules leave out (see Dues#countsAsOf). */
    private Map<Long, BigDecimal> personTotals(String sheetId, Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (Long id : byId.keySet()) {
            totals.put(id, BigDecimal.ZERO.setScale(2));
        }
        LocalDate today = sheets.today(sheetId);
        for (Claim c : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            Person person = byId.get(c.getPersonId());
            if (person == null || !Dues.countsAsOf(person, c, period.getToDate(), today) || c.getDate().isBefore(period.getFromDate())
                    || c.getDate().isAfter(period.getToDate())) {
                continue;
            }
            BigDecimal figure = dues.figure(sheetId, person, c);
            if (figure != null) {
                totals.merge(person.getId(), figure, BigDecimal::add);
            }
        }
        return totals;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet");
    }
}
