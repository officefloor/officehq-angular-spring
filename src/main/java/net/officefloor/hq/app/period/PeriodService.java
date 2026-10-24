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
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The periods of each sheet: uniquely named, none overlapping another. A period can be closed, which
 * fixes its due-total then. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RollupService rollup;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, ClaimRepository claims,
            PersonRepository people, RollupService rollup, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's periods, by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        if (to.isBefore(from)) {
            throw new PeriodRefusedException("to before from");
        }
        if (periods.findBySheetIdAndNameKey(sheetId, Period.keyOf(name)).isPresent()) {
            throw new PeriodRefusedException("name taken");
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefusedException("overlaps");
        }
        Period period = periods.saveAndFlush(new Period(sheetId, name, from, to));
        audit.change("PERIOD_ADDED", sheetId, user, "period=" + period.getName(), "from=" + period.getFrom(),
                "to=" + period.getTo());
        return period;
    }

    /** Close the period, fixing its due-total now; refused when it is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        Period period = periods.lockByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (period.isClosed()) {
            throw new PeriodRefusedException("already closed");
        }
        BigDecimal total = dueTotalOf(period);
        period.close(total);
        periods.flush();
        audit.change("PERIOD_CLOSED", sheetId, user, "period=" + period.getName(), "total=" + total.toPlainString());
        return period;
    }

    /** The due-total of the period now: the sum of the figures of the counting claims dated in it (a
     * claim with no figure adds nothing, nor does an OPEN claim of a person on hold). */
    private BigDecimal dueTotalOf(Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(period.getSheetId()).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(period.getSheetId())) {
            if (!period.overlaps(claim.getDate(), claim.getDate())) continue;
            Person person = byId.get(claim.getPersonId());
            if (person == null || !RollupService.countsLive(claim, person)) continue;
            BigDecimal figure = rollup.figureOf(claim, person);
            if (figure != null) total = total.add(figure);
        }
        return total;
    }
}
