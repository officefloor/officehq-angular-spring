package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.CorrectionRefusedException;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The people's plans: an amount from a start date every so many days (at least 1), or monthly on a
 * day of the month (1 to 31), one or the other, up to an end date on or after it. Generating makes, for every plan on the sheet in the order they were added, a DRAFT
 * claim with the plan's amount and an empty note for each plan date up to its end and the sheet's
 * today that it has made none for yet, dated that day and added by every rule of the claim form. A
 * date the form refuses is skipped, and tried again by the next generate. Each claim is made in its
 * own transaction, so one refused leaves the others made.
 */
@Service
public class ClaimPlanService {

    private final ClaimPlanRepository plans;
    private final ClaimPlanDateRepository dates;
    private final PersonRepository people;
    private final SheetRepository sheetRows;
    private final SheetService sheets;
    private final ClaimService claims;
    private final TransactionTemplate transaction;
    private final Audit audit;

    public ClaimPlanService(ClaimPlanRepository plans, ClaimPlanDateRepository dates, PersonRepository people,
            SheetRepository sheetRows, SheetService sheets, ClaimService claims,
            PlatformTransactionManager transactions, Audit audit) {
        this.plans = plans;
        this.dates = dates;
        this.people = people;
        this.sheetRows = sheetRows;
        this.sheets = sheets;
        this.claims = claims;
        this.transaction = new TransactionTemplate(transactions);
        this.audit = audit;
    }

    /** The sheet's plans in the order they were added. */
    @Transactional(readOnly = true)
    public List<ClaimPlan> plans(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** How many claims each of the sheet's plans has made so far, by plan id (those with none left out). */
    @Transactional(readOnly = true)
    public Map<Long, Integer> made(String sheetId) {
        Map<Long, Integer> made = new HashMap<>();
        for (ClaimPlanDate d : dates.findBySheetId(sheetId)) made.merge(d.getPlanId(), 1, Integer::sum);
        return made;
    }

    /** The sheet's today, from which a plan's next dates are counted. */
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    @Transactional
    public ClaimPlan add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate start,
            Integer every, Integer day, LocalDate end) {
        sheetRows.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if ((every == null) == (day == null) || end.isBefore(start)) throw new PlanRefusedException();
        if (every != null && every < 1) throw new PlanRefusedException();
        if (day != null && (day < 1 || day > 31)) throw new PlanRefusedException();
        ClaimPlan plan = plans.saveAndFlush(new ClaimPlan(sheetId, personId, amount, start, every, day, end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user.strip() + " name=" + person.getName()
                + " amount=" + amount.toPlainString() + " start=" + start
                + (day != null ? " day=" + day : " every=" + every) + " end=" + end);
        return plan;
    }

    /** Make the plans' claims due by the sheet's today: how many were made. */
    public int generate(String sheetId, String user) {
        LocalDate today = sheets.today(sheetId);
        int made = 0;
        for (ClaimPlan plan : plans(sheetId)) {
            for (LocalDate on : plan.datesUpTo(today)) {
                try {
                    Boolean done = transaction.execute(status -> make(sheetId, user, plan, on));
                    if (Boolean.TRUE.equals(done)) made++;
                } catch (PeriodClosedException | PersonRetiredException | PersonNotFoundException
                        | CorrectionRefusedException refused) {
                    // skipped: tried again next time
                }
            }
        }
        return made;
    }

    /** Make the plan's claim for the date unless it has made one for it: whether it made one. */
    private boolean make(String sheetId, String user, ClaimPlan plan, LocalDate on) {
        sheetRows.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (dates.existsByPlanIdAndMadeOn(plan.getId(), on)) return false;
        claims.add(sheetId, user, plan.getPersonId(), plan.getAmount(), on, "", null);
        dates.saveAndFlush(new ClaimPlanDate(sheetId, plan.getId(), on));
        return true;
    }
}
