package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
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
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ClaimService claims;
    private final Audit audit;
    private final TransactionTemplate transaction;

    public PlanService(PlanRepository plans, PersonRepository people, SheetService sheets, SheetRepository sheetRows,
            ClaimService claims, Audit audit, PlatformTransactionManager transactions) {
        this.plans = plans;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.claims = claims;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** The sheet's plans in the order they were added. */
    @Transactional(readOnly = true)
    public List<Plan> list(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a plan to a person: refused for an amount not above 0.00, neither or both of an interval and a
     *  day of the month, an interval below 1 day, a day of the month outside 1 to 31, or an end before the
     *  start. No start means the sheet's today. */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (amount.signum() <= 0) throw refused("The amount must be more than 0.00.");
        if ((every == null) == (day == null)) throw refused("Give either every (days) or a day of the month.");
        if (every != null && every < 1) throw refused("Every must be a whole number of days, at least 1.");
        if (day != null && (day < 1 || day > 31)) throw refused("The day of the month must be 1 to 31.");
        LocalDate from = start != null ? start : sheets.today(sheetId);
        if (end.isBefore(from)) throw refused("The end date cannot be before the start date.");
        Plan plan = plans.saveAndFlush(new Plan(sheetId, personId, amount.setScale(2), from, every, day,
                end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + plan.getAmount().toPlainString() + " start=" + from + (day != null ? " day=" + day : " every=" + every) + " end=" + end);
        return plan;
    }

    /** For every plan on the sheet, in the order they were added, make a DRAFT claim of its amount, dated
     *  the plan date with an empty note, for each plan date up to its end and the sheet's today that has
     *  none yet. Each claim is added by the claim form's rules, on its own: a date the form refuses is
     *  skipped and tried again next time. The claims made, oldest first. */
    public List<Claim> generate(String sheetId, String user) {
        sheets.load(sheetId);
        LocalDate today = sheets.today(sheetId);
        List<Claim> made = new ArrayList<>();
        for (Plan plan : plans.findBySheetIdOrderByIdAsc(sheetId)) {
            for (LocalDate date : plan.datesUpTo(today)) {
                if (plan.hasGenerated(date)) continue;
                try {
                    Claim claim = transaction.execute((status) -> generateOn(sheetId, user, plan.getId(), date));
                    if (claim != null) made.add(claim);
                } catch (ResponseStatusException refused) {
                    // The claim form refuses the date: it is tried again by the next generate.
                }
            }
        }
        return made;
    }

    /** Make the plan's claim for the date, unless it has one already. */
    private Claim generateOn(String sheetId, String user, long planId, LocalDate date) {
        Plan plan = plans.findForUpdate(planId).orElse(null);
        if (plan == null || plan.hasGenerated(date)) return null;
        Claim claim = claims.add(sheetId, user, plan.getPersonId(), plan.getAmount(), date, "", null, null, null);
        plan.markGenerated(date);
        plans.saveAndFlush(plan);
        return claim;
    }

    private static ResponseStatusException refused(String why) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, why);
    }
}
