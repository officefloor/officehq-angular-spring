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
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's periods: each named uniquely on the sheet, from and to a date inclusive, none
 * overlapping. Closing a period fixes its due-total at that moment: the figures of the claims that
 * count whose date falls in it.
 */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService claimService;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, ClaimRepository claims,
            PersonRepository people, ClaimService claimService, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.claimService = claimService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new PeriodDatesException();
        // Lock the sheet so two users adding periods at once cannot overlap or share a name.
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (periods.existsBySheetIdAndName(sheetId, name)) throw new DuplicatePeriodNameException();
        if (periods.findBySheetIdOrderByFromDateAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodOverlapException();
        }
        Period saved = periods.saveAndFlush(new Period(sheetId, name, from, to));
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user.strip()
                + " period=" + saved.getName() + " from=" + saved.getFromDate() + " to=" + saved.getToDate());
        return saved;
    }

    @Transactional
    public Period close(String sheetId, String user, Long id) {
        // Lock the sheet so no claim in the period changes while its due-total is fixed.
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Period period = periods.findByIdAndSheetId(id, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (period.isClosed()) throw new PeriodClosedException();
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !claim.getStatus().counts() || !period.contains(claim.getDate())) continue;
            BigDecimal figure = claimService.figureOf(sheetId, person, claim);
            if (figure != null) total = total.add(figure);
        }
        period.close(total);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user.strip()
                + " period=" + saved.getName() + " total=" + total.setScale(2).toPlainString());
        return saved;
    }
}
