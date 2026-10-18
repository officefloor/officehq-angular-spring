package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RollupService rollups;
    private final SheetService sheets;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, ClaimRepository claims, PersonRepository people,
            RollupService rollups, SheetService sheets, Audit audit) {
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.rollups = rollups;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's periods, by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /**
     * Add a period: refused when its to-date is before its from-date, when another period of the sheet
     * has its name (ignoring case), or when it shares a date with another. The sheet is locked so two
     * additions are checked one at a time (and is started when it was never loaded before).
     */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.lock(sheetId, user);
        String trimmed = name.trim();
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts");
        }
        List<Period> all = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        String key = trimmed.toLowerCase(Locale.ROOT);
        if (all.stream().anyMatch(p -> p.getName().toLowerCase(Locale.ROOT).equals(key))) throw nameInUse();
        if (all.stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another");
        }
        Period saved;
        try {
            saved = periods.saveAndFlush(new Period(sheetId, trimmed, from, to));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user
                + " period=" + saved.getName() + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        return saved;
    }

    /**
     * Close a period, fixing its due-total now: the sum of the figures (those with one) of the open and
     * settled claims dated in it (not a held person's open ones). A period already closed is refused.
     */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        sheets.lock(sheetId, user);
        Period period = periods.findByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed");
        }
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(c.getPersonId());
            if (person == null || !RollupService.counts(person, c) || !period.contains(c.getDate())) continue;
            BigDecimal figure = rollups.figure(sheetId, person, c);
            if (figure != null) total = total.add(figure);
        }
        period.close(total);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user
                + " period=" + saved.getName() + " total=" + total.toPlainString());
        return saved;
    }

    /** Refuse a change to a claim dated {@code date} when a closed period of the sheet contains it. */
    @Transactional(readOnly = true)
    public void requireOpenOn(String sheetId, LocalDate date) {
        if (periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .anyMatch(p -> p.isClosed() && p.contains(date))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That claim is dated in a closed period");
        }
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use");
    }
}
