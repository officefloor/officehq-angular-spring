package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlanService {

    private final PlanRepository plans;
    private final PlanClaimRepository made;
    private final PersonRepository people;
    private final ClaimService claims;
    private final SheetService sheets;
    private final Audit audit;
    private final TransactionTemplate eachClaim;

    public PlanService(PlanRepository plans, PlanClaimRepository made, PersonRepository people, ClaimService claims,
            SheetService sheets, Audit audit, PlatformTransactionManager transactions) {
        this.plans = plans;
        this.made = made;
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.audit = audit;
        this.eachClaim = new TransactionTemplate(transactions);
    }

    /** The sheet's plans, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Plan> list(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** How many claims each of the sheet's plans has made so far, by plan id (none: absent). */
    @Transactional(readOnly = true)
    public Map<Long, Long> madeCounts(String sheetId) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : made.countsBySheet(sheetId)) {
            out.put((Long) row[0], (Long) row[1]);
        }
        return out;
    }

    /** Add a plan for a person on the sheet; a missing start takes the sheet's today. Refused (404)
     *  when the sheet has no such person, and (422) when the end is before the start, or the plan
     *  does not have exactly one of an interval of one day or more and a day of the month (1 to 31). */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate from = start != null ? start : sheets.today(sheetId);
        if ((every == null) == (day == null)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A plan repeats either every so many days or monthly on a day");
        }
        if (every != null && every < 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A plan repeats every 1 day or more");
        }
        if (day != null && (day < 1 || day > 31)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A plan's day of the month is 1 to 31");
        }
        if (end.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A plan cannot end before it starts");
        }
        Plan plan = plans.saveAndFlush(new Plan(sheetId, personId, amount, from, every, day, end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + amount.setScale(2).toPlainString() + " start=" + from + (day != null ? " day=" + day : " every=" + every)
                + " end=" + end);
        return plan;
    }

    /** Pause (or, with {@code on} false, resume) one of the sheet's plans on the sheet's today.
     *  Refused (404) when the sheet has no such plan, and (409) when pausing a paused plan or
     *  resuming a running one. */
    @Transactional
    public Plan setPaused(String sheetId, String user, long planId, boolean on) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        Plan plan = plans.findByIdAndSheetId(planId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan"));
        if (plan.isPaused() == on) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    on ? "The plan is paused already" : "The plan is not paused");
        }
        LocalDate today = sheets.today(sheetId);
        if (on) {
            plan.pause(today);
        } else {
            plan.resume(today);
        }
        plans.saveAndFlush(plan);
        String name = people.findById(plan.getPersonId()).map(Person::getName).orElse("");
        audit.recordOnCommit((on ? "PLAN_PAUSED" : "PLAN_RESUMED") + " sheet=" + sheetId + " by=" + user
                + " name=" + name + " start=" + plan.getStart());
        return plan;
    }

    /** For each of the sheet's plans in the order they were added, add a DRAFT claim of its amount
     *  with an empty note for each of its dates up to its end and the sheet's today that has none
     *  yet and that it does not skip for a pause, dated that day, by the claim form's rules. Each claim is its own change: a date the
     *  claim form refuses is skipped (and tried again on the next generate). Resolves to the
     *  claims made, in the order they were made. */
    public List<Claim> generate(String sheetId, String user) {
        sheets.load(sheetId, user);
        LocalDate today = sheets.today(sheetId);
        List<Claim> out = new ArrayList<>();
        for (Plan plan : list(sheetId)) {
            for (LocalDate on : plan.datesUpTo(today)) {
                Claim claim;
                try {
                    claim = eachClaim.execute(status -> makeClaim(plan, user, on));
                } catch (ResponseStatusException refused) {
                    continue;
                }
                if (claim != null) {
                    out.add(claim);
                }
            }
        }
        return out;
    }

    /** The plan's claim for a date, in the transaction under way; null when it has one already. */
    private Claim makeClaim(Plan plan, String user, LocalDate on) {
        sheets.lock(plan.getSheetId());
        if (made.existsByPlanIdAndOnDate(plan.getId(), on)) {
            return null;
        }
        Claim claim = claims.add(plan.getSheetId(), user, plan.getPersonId(), plan.getAmount(), on, "", null, null, null);
        made.saveAndFlush(new PlanClaim(plan.getId(), on, claim.getId()));
        return claim;
    }
}
