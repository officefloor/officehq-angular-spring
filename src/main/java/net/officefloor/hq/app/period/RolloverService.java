package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.util.function.Function;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
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
 * period, and so to their carried figure for it. A fiscal year rolled over again, its last rollover not undone, adds
 * no opening balances a second time. Each rollover is kept, so the last can be undone on the day it was made.
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
    private final RolloverRepository rollovers;
    private final ClaimRepository claims;

    public RolloverService(PeriodService periods, PeriodRepository repository, SheetRepository sheets,
            PersonService people, RollupService rollup, RoleService roles, Audit audit, RolloverRepository rollovers,
            ClaimRepository claims) {
        this.rollovers = rollovers;
        this.claims = claims;
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
        Rollover record = new Rollover(sheetId, fy, user, today(sheetId));
        made.forEach(p -> record.addPeriod(p.getId()));
        String next = "FY" + (year + 1);
        Period first = periods.list(sheetId).stream().filter(p -> next.equals(fiscalYears.apply(p.getFromDate())))
                .min(Comparator.comparing(Period::getFromDate)).orElse(null);
        boolean repeated = rollovers.existsBySheetIdAndFyAndUndoneFalse(sheetId, fy);
        if (first != null && !repeated) {
            for (Person person : people.list(sheetId)) {
                BigDecimal amount = open.get(person.getId());
                if (amount == null || amount.signum() == 0) {
                    continue;
                }
                first.addOpening(person.getId(), amount);
                record.addOpening(first.getId(), person.getId(), amount);
                audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                        + " period=" + first.getName() + " amount=" + amount.toPlainString());
            }
            repository.saveAndFlush(first);
        }
        rollovers.save(record);
        audit.recordOnCommit("ROLLOVER sheet=" + sheetId + " by=" + user + " fy=" + fy + " periods=" + made.size());
        return made;
    }

    /**
     * Undo the sheet's last rollover: the periods it generated are removed and the opening balances it added taken back.
     * Refused unless the user is a steward and the one who made it, on the sheet's today it was made, and no claim
     * (other than a deleted one) is dated in a period it generated.
     */
    @Transactional
    public void undo(String sheetId, String user) {
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        Rollover last = rollovers.findFirstBySheetIdOrderByIdDesc(sheetId).filter(r -> !r.isUndone())
                .orElseThrow(() -> new RolloverUndoRefusedException("There is no rollover to undo on sheet " + sheetId));
        if (!roles.isSteward(sheetId, user) || !last.getMadeBy().equals(user)) {
            throw new RolloverUndoRefusedException("The rollover of " + last.getFy()
                    + " can only be undone by the steward who made it");
        }
        if (!last.getMadeOn().equals(today(sheetId))) {
            throw new RolloverUndoRefusedException("The rollover of " + last.getFy()
                    + " can only be undone on the day it was made");
        }
        List<Period> generated = repository.findAllById(last.getPeriodIds());
        boolean claimed = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> c.getStatus() != ClaimStatus.DELETED)
                .anyMatch(c -> generated.stream().anyMatch(p -> p.contains(c.getDate())));
        if (claimed) {
            throw new RolloverUndoRefusedException("The rollover of " + last.getFy()
                    + " cannot be undone: a claim now belongs to a period it generated");
        }
        for (RolloverOpening opening : last.getOpenings()) {
            if (last.getPeriodIds().contains(opening.getPeriodId())) {
                continue;
            }
            repository.findById(opening.getPeriodId()).ifPresent(p -> {
                p.takeOpening(opening.getPersonId(), opening.getAmount());
                repository.save(p);
            });
        }
        repository.deleteAll(generated);
        last.markUndone();
        rollovers.save(last);
        audit.recordOnCommit("ROLLOVER_UNDONE sheet=" + sheetId + " by=" + user + " fy=" + last.getFy());
    }

    private LocalDate today(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(() -> new SheetNotFoundException(sheetId)).getToday();
    }
}
