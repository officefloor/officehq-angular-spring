package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PersonRepository people;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetRows,
            ClaimRepository claims, ClaimService claimService, PersonRepository people, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
        this.audit = audit;
    }

    /** The sheet's periods in date order. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** Add a period, refused when its name is in use on the sheet, its to-date is before its
     *  from-date, or it shares a day with another of the sheet's periods. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.load(sheetId);
        // Periods of a sheet are added one at a time, so two added at once cannot overlap.
        sheetRows.findForUpdate(sheetId);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date.");
        }
        if (periods.existsBySheetIdAndNameKey(sheetId, Period.keyOf(name))) {
            throw nameInUse();
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period overlaps another period.");
        }
        Period saved;
        try {
            saved = periods.saveAndFlush(new Period(sheetId, name, from, to));
        } catch (DataIntegrityViolationException raced) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " from=" + saved.getFrom() + " to=" + saved.getTo());
        return saved;
    }

    /** Close a period, fixing its due-total at this moment: the figures of the open and settled claims
     *  dated in it, skipping those that are n/a and the open claims of people on hold. Refused when the period is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long id) {
        // Locked as claim changes lock it, so no claim in the period changes while it is closed.
        sheetRows.findForUpdate(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed.");
        }
        Map<Long, Person> peopleById = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = peopleById.get(claim.getPersonId());
            if (person == null) continue;
            boolean counts = claim.getStatus() == ClaimStatus.SETTLED
                    || (claim.getStatus() == ClaimStatus.OPEN && !person.isHeld());
            if (!counts || !period.contains(claim.getDate())) continue;
            BigDecimal figure = claimService.figure(sheetId, person, claim);
            if (figure != null) total = total.add(figure);
        }
        period.close(total);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + total.toPlainString());
        return saved;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on the sheet.");
    }
}
