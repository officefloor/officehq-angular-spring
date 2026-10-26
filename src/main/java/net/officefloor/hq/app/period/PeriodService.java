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
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.settings.SettingsService;
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
        BigDecimal total = personTotals.values().stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        period.close(total, personTotals);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + by + " period=" + saved.getName()
                + " total=" + total.toPlainString());
        return saved;
    }

    /**
     * Reopens a closed period, making its due-total live again. Refused unless the user's role may reopen (a steward by
     * the standing rule), or when the period is not closed.
     */
    @Transactional
    public Period reopen(String by, String sheetId, Long id) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        settings.refuseUnlessMay(sheetId, by, "reopen");
        if (!period.isClosed()) {
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
