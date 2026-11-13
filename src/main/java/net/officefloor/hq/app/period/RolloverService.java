package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
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
 * ROLLOVER, then soft-closes (as a soft close would, in from-date order) every old-year period still open.
 * Rolling a year over again (while its last rollover stands) carries no opening balances a second
 * time. The old year's periods are those whose from-date falls in it; refused when it has none.
 * The last rollover can be undone by the steward who made it on the sheet-today it was made, unless a claim (not
 * deleted) now belongs to a period it generated: its periods are removed and its opening balances taken back.
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
    private final RolloverRepository rollovers;
    private final ClaimRepository claims;

    public RolloverService(PeriodService periodService, PeriodRepository periods, PersonRepository people,
            SheetRepository sheets, RoleService roles, Rollup rollup, Audit audit, RolloverRepository rollovers,
            ClaimRepository claims) {
        this.rollovers = rollovers;
        this.claims = claims;
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
        Rollover record = new Rollover(sheetId, year, user, sheet.getToday());
        made.forEach(p -> record.getPeriodIds().add(p.getId()));
        Period first = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> within(p.getFromDate(), newFirst, newFirst.plusYears(1)))
                .findFirst().orElse(null);
        // A fiscal year already rolled over (and not undone) has had its open figures carried once already.
        boolean repeat = rollovers.existsBySheetIdAndFyAndUndoneFalse(sheetId, year);
        if (first != null && !repeat) {
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
                BigDecimal amount = open.get(person.getId());
                if (amount == null || amount.signum() == 0) {
                    continue;
                }
                first.addOpening(person.getId(), amount);
                record.getOpenings().merge(person.getId(), amount, BigDecimal::add);
                record.setOpeningPeriodId(first.getId());
                audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                        + " period=" + first.getName() + " amount=" + amount.toPlainString());
            }
            periods.saveAndFlush(first);
        }
        rollovers.saveAndFlush(record);
        audit.recordOnCommit("ROLLOVER sheet=" + sheetId + " by=" + user + " fy=FY" + String.format("%04d", year)
                + " periods=" + made.size());
        // The old year's periods still open are soft-closed, after the ROLLOVER line.
        periodService.softCloseOpen(sheetId, user, oldPeriods);
        return made;
    }

    /** Undo the sheet's last rollover: remove the periods it generated and take back the opening balances it
     *  set. Refused when there is none (or it is already undone), when the user is not the steward who made
     *  it, when the sheet's today has moved on from the day it was made, or when a claim belongs to one of its
     *  periods. */
    @Transactional
    public void undo(String sheetId, String user) {
        // Locking the sheet makes the undo and any claim change or rollover on the sheet go one after the other.
        Sheet sheet = sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new ActionNotAllowedException("undo the rollover");
        }
        Rollover last = rollovers.findFirstBySheetIdOrderByIdDesc(sheetId).filter(r -> !r.isUndone())
                .orElseThrow(() -> new PeriodRefusedException("There is no rollover to undo."));
        if (!last.getMadeBy().equals(user)) {
            throw new PeriodRefusedException("Only " + last.getMadeBy() + " can undo the last rollover.");
        }
        if (!last.getMadeOn().equals(sheet.getToday())) {
            throw new PeriodRefusedException("The last rollover can only be undone on the day it was made.");
        }
        List<Period> made = periods.findAllById(last.getPeriodIds());
        for (Period p : made) {
            if (claims.existsBySheetIdAndStatusNotAndDueByBetween(sheetId, ClaimStatus.DELETED, p.getFromDate(),
                    p.getToDate())) {
                throw new PeriodRefusedException("A claim belongs to period " + p.getName() + ".");
            }
        }
        if (last.getOpeningPeriodId() != null && !last.getOpenings().isEmpty()) {
            periods.findByIdAndSheetId(last.getOpeningPeriodId(), sheetId).ifPresent(p -> {
                last.getOpenings().forEach(p::takeBackOpening);
                periods.saveAndFlush(p);
            });
        }
        periods.deleteAll(made);
        periods.flush();
        last.undo();
        rollovers.saveAndFlush(last);
        audit.recordOnCommit("ROLLOVER_UNDONE sheet=" + sheetId + " by=" + user + " fy=FY"
                + String.format("%04d", last.getFy()));
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate before) {
        return !date.isBefore(from) && date.isBefore(before);
    }
}
