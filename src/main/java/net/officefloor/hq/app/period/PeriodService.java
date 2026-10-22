package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
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
     * Close a period, fixing its due-total at this moment: the sum of the due figures of the OPEN and
     * SETTLED claims dated in it, each at the grade its person has in force on its date (a claim with no figure adds nothing),
     * leaving out the OPEN claims of anyone on hold. Refused when the period is already closed.
     */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        // Take the sheet's lock first, so no claim in the period changes while its total is fixed.
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed.");
        }
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, person -> person));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !person.countsLive(claim.getStatus()) || !period.contains(claim.getDate())) {
                continue;
            }
            BigDecimal figure = dues.due(claim, person.gradeOn(claim.getDate()));
            if (figure != null) {
                total = total.add(figure);
            }
        }
        period.close(total);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + total.toPlainString());
        return saved;
    }

    /**
     * Reopen a closed period: its due-total is live again, the claims dated in it counting at their
     * current figures. Refused for anyone but a steward, and when the period is not closed.
     */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can reopen a period.");
        }
        if (!period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is not closed.");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + saved.getName());
        return saved;
    }

    /** Refuse a change to a claim dated inside one of the sheet's closed periods. */
    public void refuseIfClosed(String sheetId, LocalDate date) {
        if (periods.findBySheetIdAndClosedTrue(sheetId).stream().anyMatch(p -> p.contains(date))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is dated inside a closed period.");
        }
    }
}
