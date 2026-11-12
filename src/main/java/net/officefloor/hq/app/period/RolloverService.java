package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.util.function.Function;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rolls a fiscal year over: generates the next fiscal year's twelve periods as the fiscal year generator does, and adds
 * each person's open figure at the end of the old year's last period to their opening balance for the new year's first
 * period, and so to their carried figure for it.
 */
@Service
public class RolloverService {

    private final PeriodService periods;
    private final PeriodRepository repository;
    private final SheetRepository sheets;
    private final PersonService people;
    private final RollupService rollup;
    private final RoleService roles;
    private final Audit audit;

    public RolloverService(PeriodService periods, PeriodRepository repository, SheetRepository sheets,
            PersonService people, RollupService rollup, RoleService roles, Audit audit) {
        this.periods = periods;
        this.repository = repository;
        this.sheets = sheets;
        this.people = people;
        this.rollup = rollup;
        this.roles = roles;
        this.audit = audit;
    }

    /**
     * Roll the fiscal year FY{@code year} over; the periods made. Refused unless the user is a steward and the year has
     * periods on the sheet.
     */
    @Transactional
    public List<Period> rollover(String sheetId, String user, int year) {
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        String fy = "FY" + year;
        if (!roles.isSteward(sheetId, user)) {
            throw new RolloverForbiddenException(fy, user);
        }
        Function<LocalDate, String> fiscalYears = periods.fiscalYears(sheetId);
        List<Period> old = periods.list(sheetId).stream().filter(p -> fy.equals(fiscalYears.apply(p.getFromDate())))
                .toList();
        if (old.isEmpty()) {
            throw new PeriodRefusedException("The fiscal year " + fy + " has no periods on sheet " + sheetId);
        }
        Period last = old.stream().max(Comparator.comparing(Period::getToDate)).orElseThrow();
        Map<Long, BigDecimal> open = rollup.openAtEndOf(sheetId, old, last.getToDate());
        List<Period> made = periods.generateFiscalYear(sheetId, user, year + 1);
        String next = "FY" + (year + 1);
        Period first = periods.list(sheetId).stream().filter(p -> next.equals(fiscalYears.apply(p.getFromDate())))
                .min(Comparator.comparing(Period::getFromDate)).orElse(null);
        if (first != null) {
            for (Person person : people.list(sheetId)) {
                BigDecimal amount = open.get(person.getId());
                if (amount == null || amount.signum() == 0) {
                    continue;
                }
                first.addOpening(person.getId(), amount);
                audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                        + " period=" + first.getName() + " amount=" + amount.toPlainString());
            }
            repository.saveAndFlush(first);
        }
        audit.recordOnCommit("ROLLOVER sheet=" + sheetId + " by=" + user + " fy=" + fy + " periods=" + made.size());
        return made;
    }
}
