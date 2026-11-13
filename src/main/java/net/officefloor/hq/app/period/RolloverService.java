package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.ActionNotAllowedException;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The year end rollover, by a steward only: generates the next fiscal year's twelve periods as the fiscal year
 * generator does, then adds each person's open figure at the end of the old year's last period to their opening
 * balance for the new year's first period (an OPENING_SET line each, none for a figure of 0.00), then writes
 * ROLLOVER. The old year's periods are those whose from-date falls in it; refused when it has none.
 */
@Service
public class RolloverService {

    private final PeriodService periodService;
    private final PeriodRepository periods;
    private final PersonRepository people;
    private final SheetRepository sheets;
    private final RoleService roles;
    private final Rollup rollup;
    private final Audit audit;

    public RolloverService(PeriodService periodService, PeriodRepository periods, PersonRepository people,
            SheetRepository sheets, RoleService roles, Rollup rollup, Audit audit) {
        this.periodService = periodService;
        this.periods = periods;
        this.people = people;
        this.sheets = sheets;
        this.roles = roles;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Roll the fiscal year (named by the calendar year its first day falls in) over: the periods made. */
    @Transactional
    public List<Period> rollover(String sheetId, String user, int year) {
        // Locking the sheet makes the rollover and any claim change or close on the sheet go one after the other.
        Sheet sheet = sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new ActionNotAllowedException("roll the year over");
        }
        LocalDate oldFirst = LocalDate.of(year, sheet.getFiscalStartMonth(), 1);
        LocalDate newFirst = oldFirst.plusYears(1);
        List<Period> oldPeriods = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> within(p.getFromDate(), oldFirst, newFirst)).toList();
        if (oldPeriods.isEmpty()) {
            throw new PeriodRefusedException("Fiscal year " + year + " has no periods.");
        }
        LocalDate end = oldPeriods.stream().map(Period::getToDate).max(Comparator.naturalOrder()).orElseThrow();
        Map<Long, BigDecimal> open = rollup.openByPerson(sheetId, oldPeriods, end);
        List<Period> made = periodService.generateFiscalYear(sheetId, user, year + 1);
        Period first = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> within(p.getFromDate(), newFirst, newFirst.plusYears(1)))
                .findFirst().orElse(null);
        if (first != null) {
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
                BigDecimal amount = open.get(person.getId());
                if (amount == null || amount.signum() == 0) {
                    continue;
                }
                first.addOpening(person.getId(), amount);
                audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                        + " period=" + first.getName() + " amount=" + amount.toPlainString());
            }
            periods.saveAndFlush(first);
        }
        audit.recordOnCommit("ROLLOVER sheet=" + sheetId + " by=" + user + " fy=FY" + String.format("%04d", year)
                + " periods=" + made.size());
        return made;
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate before) {
        return !date.isBefore(from) && date.isBefore(before);
    }
}
