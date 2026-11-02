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
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, ClaimRepository claims,
            PersonRepository people, Dues dues, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.dues = dues;
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

    /** Close a period, fixing its due-total at what it is now; refused when it is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        sheets.load(sheetId, user);
        Period period = periods.lockByIdAndSheetId(periodId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed");
        }
        BigDecimal total = dueTotal(sheetId, period);
        period.close(total);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + total.toPlainString());
        return saved;
    }

    /** A period's due-total: the due figures of the claims that count whose date falls in it,
     *  skipping those with no due. */
    private BigDecimal dueTotal(String sheetId, Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(c.getPersonId());
            if (person == null || !c.getStatus().counts() || c.getDate().isBefore(period.getFromDate())
                    || c.getDate().isAfter(period.getToDate())) {
                continue;
            }
            BigDecimal figure = dues.figure(sheetId, person, c);
            if (figure != null) {
                total = total.add(figure);
            }
        }
        return total;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet");
    }
}
