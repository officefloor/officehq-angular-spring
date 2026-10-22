package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
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
     * Close a period, fixing at this moment each person's due-total for it and its own, their sum. A person's is the sum of the due figures,
     * as computed now, of the claims dated in it that were OPEN, PENDING or SETTLED at the end of its last day by their status log, each at
     * the grade its person has in force on its date (a claim with no figure adds nothing), leaving out those still OPEN or PENDING and dated
     * within their person's hold, unless bound by a statement, and those deferred on today. Refused when the period is already closed, or
     * for a role the sheet's permission matrix does not allow.
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
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, person -> person));
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        byId.keySet().forEach(id -> totals.put(id, BigDecimal.ZERO.setScale(2)));
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !period.contains(claim.getDate())) {
                continue;
            }
            ClaimStatus then = claim.statusAt(period.getToDate());
            if (then == null || !then.counts() || claim.isDeferredOn(today)
                    || (claim.getStatus().isOpen() && !claim.isStated() && person.heldOn(claim.getDate()))) {
                continue;
            }
            BigDecimal figure = dues.due(claim, person.gradeOn(claim.getDate()));
            if (figure != null) {
                totals.merge(person.getId(), figure, BigDecimal::add);
            }
        }
        period.close(totals);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + saved.getClosedTotal().toPlainString());
        return saved;
    }

    /**
     * Reopen a closed period: its due-total is live again, the claims dated in it counting at their
     * current figures. Refused for a role the sheet's permission matrix
     * does not allow (anyone but a steward by default), and when the period is not closed.
     */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        LocalDate today = sheets.lock(sheetId).getToday();
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        if (!roles.permits(sheetId, user, "reopen")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not reopen a period.");
        }
        if (!period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is not closed.");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + saved.getName());
        return saved;
    }

    /**
     * Give out the number for a claim dated on a date as it is opened, holding the sheet's lock until
     * the caller's transaction ends. While the sheet numbers by period (see {@link Sheet#numbersByPeriod})
     * it is the next within the period the date falls in, {@code <period name>-<n>}, or {@code X-<n>}
     * when it falls in none; otherwise it is the sheet's next sheet-wide number.
     */
    @Transactional
    public String nextClaimNumber(String sheetId, LocalDate date) {
        Sheet sheet = sheets.lock(sheetId);
        if (!sheet.numbersByPeriod()) {
            return String.valueOf(sheet.nextClaimNumber());
        }
        return periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> p.contains(date))
                .findFirst()
                .map(p -> p.getName() + "-" + p.nextClaimNumber())
                .orElseGet(() -> "X-" + sheet.nextUnperiodClaimNumber());
    }

    /** Refuse a change to a claim dated inside one of the sheet's closed periods. */
    public void refuseIfClosed(String sheetId, LocalDate date) {
        if (periods.findBySheetIdAndClosedTrue(sheetId).stream().anyMatch(p -> p.contains(date))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is dated inside a closed period.");
        }
    }
}
