package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The people's plans on a sheet. Generating makes, for every plan in the order they were added, a DRAFT claim of
 * the plan's amount for each plan date (the start, then every N days) up to its end and the sheet's today that has
 * none yet, dated that day with an empty note, by every rule of the claim form. A date the form refuses is skipped,
 * writing nothing, and tried again the next time.
 */
@Service
public class Plans {

    private final PlanRepository plans;
    private final PlanClaimRepository made;
    private final PersonRepository people;
    private final ClaimService claims;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    public Plans(PlanRepository plans, PlanClaimRepository made, PersonRepository people, ClaimService claims,
            SheetService sheets, SheetRepository sheetRows, ApplicationEventPublisher events,
            PlatformTransactionManager transactions) {
        this.plans = plans;
        this.made = made;
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** The plans on the sheet in the order they were added. */
    @Transactional(readOnly = true)
    public List<Plan> list(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** How many claims the plan has made so far. */
    @Transactional(readOnly = true)
    public long generated(Plan plan) {
        return made.countByPlanId(plan.getId());
    }

    /** Add a plan to the person; refused when its end is before its start or its interval is under one day. */
    @Transactional
    public void add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, int every,
            LocalDate end) {
        if (every < 1) {
            throw new PlanRefused("Enter a whole number of days, at least 1.");
        }
        if (end.isBefore(start)) {
            throw new PlanRefused("The end cannot be before the start.");
        }
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        plans.saveAndFlush(new Plan(sheetId, personId, amount, start, every, end));
        events.publishEvent(new PlanAdded(sheetId, user, person.getName(), amount, start, every, end));
    }

    /** Make the claims the plans are due up to the sheet's today; returns how many were made. */
    public int generate(String sheetId, String user) {
        sheets.load(sheetId, user);
        LocalDate today = sheets.today(sheetId);
        int count = 0;
        for (Plan plan : list(sheetId)) {
            LocalDate last = plan.getEnd().isBefore(today) ? plan.getEnd() : today;
            for (LocalDate on = plan.getStart(); !on.isAfter(last); on = on.plusDays(plan.getEvery())) {
                if (makeClaim(sheetId, user, plan, on)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Make the plan's claim on the date, each in its own change: false when it has one or the form refuses it. */
    private boolean makeClaim(String sheetId, String user, Plan plan, LocalDate on) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                sheetRows.lockById(sheetId);
                if (made.existsByPlanIdAndOn(plan.getId(), on)) {
                    return false;
                }
                claims.add(sheetId, user, plan.getPersonId(), plan.getAmount(), on, "");
                made.saveAndFlush(new PlanClaim(plan.getId(), on));
                return true;
            }));
        } catch (RuntimeException refused) {
            return false; // refused by the claim form's rules: nothing written, tried again next time
        }
    }
}
