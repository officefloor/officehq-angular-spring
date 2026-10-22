package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

/**
 * The plans on a sheet: listed, added on a person, and generated, each plan date up to the sheet's
 * today making a DRAFT claim by the claim form's rules.
 */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PersonRepository people;
    private final SheetService sheets;
    private final ClaimService claims;
    private final TransactionTemplate eachClaim;
    private final Audit audit;

    public PlanService(PlanRepository plans, PersonRepository people, SheetService sheets, ClaimService claims,
            PlatformTransactionManager transactions, Audit audit) {
        this.plans = plans;
        this.people = people;
        this.sheets = sheets;
        this.claims = claims;
        this.eachClaim = new TransactionTemplate(transactions);
        this.audit = audit;
    }

    /** What a generate made: the claims, in the order they were made, and the sheet's plans after it. */
    public record Generated(List<Claim> claims, List<Plan> plans) {
    }

    /** The sheet's plans in the order they were added. */
    @Transactional(readOnly = true)
    public List<Plan> list(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The sheet's today, from which a plan's next dates are counted. */
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    /**
     * Add a plan on a person: an amount (not below 0.00), claimed every N days (at least 1) or monthly on a
     * day of the month (1 to 31), exactly one of the two, from a start date to an end date; refused when the
     * end is before the start.
     */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end) {
        if (amount.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's amount cannot be below 0.00.");
        }
        if ((every == null) == (day == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A plan repeats either every N days or monthly on a day of the month.");
        }
        if (day != null && (day < 1 || day > 31)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's day of the month is 1 to 31.");
        }
        if (every != null && every < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan repeats every 1 day or more.");
        }
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan cannot end before it starts.");
        }
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        BigDecimal money = amount.setScale(2, RoundingMode.UNNECESSARY);
        Plan saved = plans.saveAndFlush(new Plan(sheetId, personId, money, start, every, day, end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + money.toPlainString() + " start=" + start + (day != null ? " day=" + day : " every=" + every) + " end=" + end);
        return saved;
    }

    /**
     * For every plan on the sheet, in the order they were added, make a DRAFT claim with the plan's amount
     * and an empty note for each plan date up to its end and the sheet's today that has none yet, dated
     * that day. Each claim is added by the claim form's rules, on its own: a date the form refuses is
     * skipped, and tried again on the next generate.
     */
    public Generated generate(String sheetId, String user) {
        LocalDate today = sheets.today(sheetId);
        List<Claim> made = new ArrayList<>();
        for (Plan plan : list(sheetId)) {
            for (LocalDate date : plan.datesUpTo(today)) {
                if (plan.hasMade(date)) {
                    continue;
                }
                try {
                    Claim claim = eachClaim.execute(status -> make(sheetId, user, plan.getId(), date));
                    if (claim != null) {
                        made.add(claim);
                    }
                } catch (ResponseStatusException refused) {
                    // the claim form refuses this date for now: it is tried again next time
                }
            }
        }
        return new Generated(made, list(sheetId));
    }

    /** Make the plan's claim for a date, unless another generate already has: null then. */
    private Claim make(String sheetId, String user, long planId, LocalDate date) {
        sheets.lock(sheetId);
        Plan plan = plans.findByIdAndSheetId(planId, sheetId).orElse(null);
        if (plan == null || plan.hasMade(date)) {
            return null;
        }
        Claim claim = claims.add(sheetId, user, plan.getPersonId(), plan.getAmount(), date, "", null);
        plan.made(date, claim.getId());
        plans.saveAndFlush(plan);
        return claim;
    }
}
