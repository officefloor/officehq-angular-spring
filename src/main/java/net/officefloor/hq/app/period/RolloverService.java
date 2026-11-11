package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The year-end rollover: a steward rolls fiscal year FY&lt;year&gt; over. The next fiscal year's
 * twelve periods are generated exactly as the fiscal year generator does; then each person's open
 * figure at the end of the old year's last period (the due of their claims that count, belonging to
 * a period of the old year, OPEN or PENDING at that period's end) is added to their opening balance
 * for the new year's first period, one OPENING_SET record each (none for a figure of 0.00); then one
 * ROLLOVER record. Refused (403) to anyone but a steward and (400) for a year with no periods.
 */
@Service
public class RolloverService {

    private final PeriodService service;
    private final PeriodRepository periods;
    private final PersonRepository people;
    private final RollupService rollup;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final RoleService roles;
    private final Audit audit;

    public RolloverService(PeriodService service, PeriodRepository periods, PersonRepository people,
            RollupService rollup, SheetService sheets, SheetRepository sheetRows, RoleService roles, Audit audit) {
        this.service = service;
        this.periods = periods;
        this.people = people;
        this.rollup = rollup;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.roles = roles;
        this.audit = audit;
    }

    /** Roll the fiscal year over: the periods made for the next one, by from-date. */
    @Transactional
    public List<Period> rollover(String sheetId, String user, int year) {
        roles.refuseViewer(sheetId, user);
        roles.refuseUnlessSteward(sheetId, user);
        int startMonth = sheets.load(sheetId, user).getFiscalStartMonth();
        // hold the sheet so no claim or period changes while the year is rolled over
        sheetRows.findLockedById(sheetId);
        String oldYear = "FY" + year;
        String newYear = "FY" + (year + 1);
        List<Period> old = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> oldYear.equals(Sheet.fiscalYearOf(p.getFromDate(), startMonth))).toList();
        if (old.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, oldYear + " has no periods to roll over.");
        }
        Period last = old.stream().max((a, b) -> a.getToDate().compareTo(b.getToDate())).orElseThrow();
        Map<Long, BigDecimal> open = rollup.openByPerson(sheetId, old, last.getToDate());
        List<Period> made = service.generateFiscalYear(sheetId, user, year + 1);
        periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> newYear.equals(Sheet.fiscalYearOf(p.getFromDate(), startMonth))).findFirst()
                .ifPresent(first -> {
                    for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
                        BigDecimal amount = open.getOrDefault(person.getId(), BigDecimal.ZERO)
                                .setScale(2, RoundingMode.HALF_UP);
                        if (amount.signum() == 0) {
                            continue;
                        }
                        first.addOpening(person.getId(), amount);
                        audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name="
                                + person.getName() + " period=" + first.getName() + " amount="
                                + amount.toPlainString());
                    }
                    periods.saveAndFlush(first);
                });
        audit.recordOnCommit("ROLLOVER sheet=" + sheetId + " by=" + user + " fy=" + oldYear + " periods=" + made.size());
        return made;
    }
}
