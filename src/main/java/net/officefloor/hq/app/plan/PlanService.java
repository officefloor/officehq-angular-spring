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
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** The plans of the people on a sheet. Generating makes, for every plan on the sheet in the order they
 * were added, a DRAFT claim of the plan's amount, dated that day with an empty note, for every plan date
 * up to the plan's end and the sheet's today it has made none for yet. Each claim is added by the claim
 * form's rules, in a change of its own: a date the form refuses is skipped, writes nothing, and is tried
 * again the next time. */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PersonRepository people;
    private final SheetRepository sheetRepository;
    private final SheetService sheets;
    private final ClaimService claims;
    private final Audit audit;
    private final TransactionTemplate eachClaim;

    public PlanService(PlanRepository plans, PersonRepository people, SheetRepository sheetRepository,
            SheetService sheets, ClaimService claims, Audit audit, PlatformTransactionManager transactions) {
        this.plans = plans;
        this.people = people;
        this.sheetRepository = sheetRepository;
        this.sheets = sheets;
        this.claims = claims;
        this.audit = audit;
        this.eachClaim = new TransactionTemplate(transactions);
        this.eachClaim.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The sheet's plans in the order they were added. */
    @Transactional(readOnly = true)
    public List<Plan> plans(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The sheet's today, which a plan's next dates are after. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    /** Add a plan for the person, every {@code every} days or monthly on {@code day} (exactly one given);
     * refused when the end is before the start, the interval is under 1 day, the day is not 1 to 31, both
     * or neither are given, or the amount is not above zero. Records PLAN_ADDED. */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (end.isBefore(start) || amount.signum() <= 0 || (every == null) == (day == null)
                || (every != null && every < 1) || (day != null && (day < 1 || day > 31))) {
            throw new PlanDatesException();
        }
        Plan plan = plans.saveAndFlush(new Plan(sheetId, personId, amount, start, every, day, end));
        audit.change("PLAN_ADDED", sheetId, user, "name=" + person.getName(),
                "amount=" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(), "start=" + start,
                every != null ? "every=" + every : "day=" + day, "end=" + end);
        return plan;
    }

    /** Make the claims every plan on the sheet is due, in the order the plans were added and, within a
     * plan, by date; the claims made, in the order they were made. Not one change: each claim is. */
    public List<Claim> generate(String sheetId, String user) {
        if (sheetRepository.findById(sheetId).isEmpty()) return List.of();
        LocalDate today = sheets.today(sheetId);
        List<Claim> made = new ArrayList<>();
        for (Plan plan : plans(sheetId)) {
            for (LocalDate date : plan.datesDue(today)) {
                try {
                    made.add(eachClaim.execute(status -> make(sheetId, user, plan.getId(), date)));
                } catch (RuntimeException refused) {
                    // The claim form refused this date: it is skipped, and tried again next time.
                }
            }
        }
        return made;
    }

    private Claim make(String sheetId, String user, long planId, LocalDate date) {
        Plan plan = plans.findByIdAndSheetId(planId, sheetId).orElseThrow(IllegalStateException::new);
        Claim claim = claims.add(sheetId, user, plan.getPersonId(), plan.getAmount(), date, "", null);
        plan.made(date, claim.getId());
        plans.saveAndFlush(plan);
        return claim;
    }
}
