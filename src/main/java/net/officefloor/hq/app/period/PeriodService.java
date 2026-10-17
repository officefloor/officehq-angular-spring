package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The periods on a sheet, in date order. A period's name is unique on its sheet regardless of case,
 * its from-date is not after its to-date, and it overlaps no other period on the sheet. Closing a
 * period fixes its due-total as it is at that moment;
 * a steward can reopen it, making its due-total live again.
 */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final RoleService roles;
    private final SheetService sheets;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, ClaimRepository claims, PersonRepository people, RateService rates,
            RoleService roles, SheetService sheets, Audit audit) {
        this.roles = roles;
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts");
        }
        sheets.load(sheetId, user);
        // Lock the sheet, so periods added at the same moment are checked against each other.
        sheets.lock(sheetId);
        for (Period other : periods.findBySheetIdOrderByFromAsc(sheetId)) {
            if (other.getName().equalsIgnoreCase(name)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A period named " + name + " exists");
            }
            if (other.overlaps(from, to)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The period overlaps " + other.getName());
            }
        }
        Period period = periods.save(new Period(sheetId, name, from, to));
        audit.change("PERIOD_ADDED", sheetId, user, "period=" + name, "from=" + from, "to=" + to);
        return period;
    }

    /**
     * Close a period, fixing its due-total: the dues of the counted claims dated in it (an open claim of a
     * person on hold is not counted), each a settled
     * claim's fixed due or else its amount at its person's grade's rate in force on its date, rounded
     * half to even to cents and raised to the sheet's floor, an overdue one's late charge added; a claim with no such rate adds nothing. A closed period cannot be closed again (409).
     */
    @Transactional
    public Period close(String sheetId, String user, String name) {
        sheets.load(sheetId, user);
        // Lock the sheet, so no claim changes while the total is taken.
        Sheet sheet = sheets.lock(sheetId).orElseThrow();
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed");
        }
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !person.counts(claim.getStatus()) || !period.contains(claim.getDate())) {
                continue;
            }
            BigDecimal due = switch (claim.getStatus()) {
                case SETTLED -> claim.getSettledDue();
                default -> sheet.withLateCharge(sheet.floored(rates.inForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate())
                        .map(rate -> claim.getAmount().multiply(rate.getRate())
                                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                        .orElse(null)), claim.getDate());
            };
            if (due != null) {
                total = total.add(due);
            }
        }
        period.close(total);
        audit.change("PERIOD_CLOSED", sheetId, user, "period=" + period.getName(), "total=" + total.toPlainString());
        return period;
    }

    /**
     * Reopen a closed period, making its due-total live again. Only a steward can reopen one (403); a period
     * that is not closed cannot be reopened (409).
     */
    @Transactional
    public Period reopen(String sheetId, String user, String name) {
        sheets.load(sheetId, user);
        // Lock the sheet, so a period is not reopened twice at the same moment.
        sheets.lock(sheetId);
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        if (roles.of(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can reopen a period");
        }
        if (!period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is not closed");
        }
        period.reopen();
        audit.change("PERIOD_REOPENED", sheetId, user, "period=" + period.getName());
        return period;
    }
}
