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
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
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
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, ClaimRepository claims,
            PersonRepository people, ClaimService claimService, Audit audit) {
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
     * Closes the period, fixing its due-total now: the figures of the counted claims dated in it, skipping n/a.
     * Refused when it is already closed.
     */
    @Transactional
    public Period close(String by, String sheetId, Long id) {
        // the sheet stays locked until the transaction ends, so no claim in the period changes while it closes
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed.");
        }
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(c.getPersonId());
            if (person == null || !c.getStatus().counts() || !period.contains(c.getDate())) {
                continue;
            }
            BigDecimal figure = claimService.figure(sheetId, person, c);
            if (figure != null) {
                total = total.add(figure);
            }
        }
        period.close(total);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + by + " period=" + saved.getName()
                + " total=" + total.toPlainString());
        return saved;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet.");
    }
}
