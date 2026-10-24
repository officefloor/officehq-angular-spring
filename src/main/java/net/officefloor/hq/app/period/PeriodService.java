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
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The periods of each sheet: uniquely named, none overlapping another. A period can be closed, which
 * fixes its due-total then, and a steward can reopen it, which makes its due-total live again. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RollupService rollup;
    private final SettingsService settings;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, ClaimRepository claims,
            PersonRepository people, RollupService rollup, SettingsService settings, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.rollup = rollup;
        this.settings = settings;
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

    /** Reopen the period, making its due-total live again; refused when the user is not a steward of
     * the sheet or the period is not closed. */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        Period period = periods.lockByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PeriodNotStewardException();
        }
        if (!period.isClosed()) {
            throw new PeriodRefusedException("not closed");
        }
        period.reopen();
        periods.flush();
        audit.change("PERIOD_REOPENED", sheetId, user, "period=" + period.getName());
        return period;
    }

    /** The due-total of the period now: the sum of what is due on the counting claims dated in it, as
     * each shows it, late charges included (a
     * claim with no figure adds nothing, nor does an unbound OPEN claim dated within one of its person's holds). */
    private BigDecimal dueTotalOf(Period period) {
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(period.getSheetId()).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByListedDescIdDesc(period.getSheetId())) {
            if (!period.overlaps(claim.getDate(), claim.getDate())) continue;
            Person person = byId.get(claim.getPersonId());
            if (person == null || !RollupService.countsLive(claim, person)) continue;
            BigDecimal figure = rollup.shownOf(claim, person);
            if (figure != null) total = total.add(figure);
        }
        return total;
    }
}
