package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
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
 * again the next time. A plan naming a descendant sheet makes its claims on that sheet, for the person of
 * the same name there, by that sheet's rules and in its audit; none while there is no such person. */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PersonRepository people;
    private final SheetRepository sheetRepository;
    private final SheetService sheets;
    private final ClaimService claims;
    private final Audit audit;
    private final TransactionTemplate eachClaim;
    private final SettingsService settings;

    public PlanService(PlanRepository plans, PersonRepository people, SheetRepository sheetRepository,
            SheetService sheets, ClaimService claims, Audit audit, PlatformTransactionManager transactions,
            SettingsService settings) {
        this.plans = plans;
        this.people = people;
        this.sheetRepository = sheetRepository;
        this.sheets = sheets;
        this.claims = claims;
        this.audit = audit;
        this.settings = settings;
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
     * or neither are given, the amount is not above zero, or the target sheet (null for none) is not one
     * of the sheet's descendants. Records PLAN_ADDED. */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end, String targetSheetId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (end.isBefore(start) || amount.signum() <= 0 || (every == null) == (day == null)
                || (every != null && every < 1) || (day != null && (day < 1 || day > 31))) {
            throw new PlanDatesException();
        }
        if (targetSheetId != null && !isDescendant(sheetId, targetSheetId)) throw new PlanSheetException();
        Plan plan = plans.saveAndFlush(new Plan(sheetId, personId, amount, start, every, day, end, targetSheetId));
        List<String> line = new ArrayList<>(List.of("name=" + person.getName(),
                "amount=" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(), "start=" + start,
                every != null ? "every=" + every : "day=" + day, "end=" + end));
        if (targetSheetId != null) line.add("sheet=" + targetSheetId);
        audit.change("PLAN_ADDED", sheetId, user, line.toArray(String[]::new));
        return plan;
    }

    /** Whether {@code candidateId} is a loaded sheet below {@code sheetId}: its child, their child, and so on. */
    private boolean isDescendant(String sheetId, String candidateId) {
        Set<String> seen = new HashSet<>();
        for (Sheet up = sheetRepository.findById(candidateId).orElse(null); up != null && seen.add(up.getId());
                up = up.getParentId() == null ? null : sheetRepository.findById(up.getParentId()).orElse(null)) {
            if (sheetId.equals(up.getParentId())) return true;
        }
        return false;
    }

    /** Pause the plan from the sheet's today; refused when it is already paused. Records PLAN_PAUSED. */
    @Transactional
    public Plan pause(String sheetId, String user, long planId) {
        Plan plan = plans.findByIdAndSheetId(planId, sheetId).orElseThrow(PlanNotFoundException::new);
        plan.pause(sheets.today(sheetId));
        return changed(sheetId, user, plan, "PLAN_PAUSED");
    }

    /** Resume the plan on the sheet's today; refused when it is not paused. Records PLAN_RESUMED. */
    @Transactional
    public Plan resume(String sheetId, String user, long planId) {
        Plan plan = plans.findByIdAndSheetId(planId, sheetId).orElseThrow(PlanNotFoundException::new);
        plan.resume(sheets.today(sheetId));
        return changed(sheetId, user, plan, "PLAN_RESUMED");
    }

    /** Change the plan's amount, from the sheet's today, for the claims generated from then on; refused
     * when it is not above zero. Records PLAN_CHANGED. Then each claim the plan generated that is still a
     * DRAFT, in the order they were made, is edited to the new amount as the claim form would edit it,
     * in a change of its own: one the form refuses keeps its amount and writes nothing. Claims in any
     * other status keep theirs. */
    public Plan changeAmount(String sheetId, String user, long planId, BigDecimal amount) {
        Plan plan = eachClaim.execute(status -> {
            Plan changing = plans.findByIdAndSheetId(planId, sheetId).orElseThrow(PlanNotFoundException::new);
            changing.changeAmount(sheets.today(sheetId), amount);
            return changed(sheetId, user, changing, "PLAN_CHANGED",
                    "amount=" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        });
        String claimSheet = plan.getTargetSheetId() == null ? sheetId : plan.getTargetSheetId();
        for (long claimId : plan.getClaimIds()) {
            try {
                eachClaim.execute(status -> claims.editDraftAmount(claimSheet, user, claimId, amount));
            } catch (RuntimeException refused) {
                // The claim form refused the edit: the claim keeps its amount.
            }
        }
        return plan;
    }

    private Plan changed(String sheetId, String user, Plan plan, String event, String... fields) {
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        Plan saved = plans.saveAndFlush(plan);
        List<String> line = new ArrayList<>(List.of("name=" + person.getName(), "start=" + plan.getStart()));
        line.addAll(List.of(fields));
        audit.change(event, sheetId, user, line.toArray(String[]::new));
        return saved;
    }

    /** Make the claims every plan on the sheet is due, in the order the plans were added and, within a
     * plan, by date; the claims made, in the order they were made. Not one change: each claim is. */
    public List<Claim> generate(String sheetId, String user) {
        if (sheetRepository.findById(sheetId).isEmpty()) return List.of();
        if (settings.roleOf(sheetId, user) == Role.VIEWER) return List.of();
        LocalDate today = sheets.today(sheetId);
        List<Claim> made = new ArrayList<>();
        for (Plan plan : plans(sheetId)) {
            if (plan.getTargetSheetId() != null && targetPerson(sheetId, plan).isEmpty()) continue;
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
        Claim claim = plan.getTargetSheetId() == null
                ? claims.add(sheetId, user, plan.getPersonId(), plan.getAmount(), date, "", null)
                : claims.add(plan.getTargetSheetId(), user,
                        targetPerson(sheetId, plan).orElseThrow(IllegalStateException::new).getId(),
                        plan.getAmount(), date, "", null);
        plan.made(date, claim.getId());
        plans.saveAndFlush(plan);
        return claim;
    }

    /** The person on the plan's target sheet with the name of the plan's person; empty when there is none. */
    private Optional<Person> targetPerson(String sheetId, Plan plan) {
        return people.findByIdAndSheetId(plan.getPersonId(), sheetId).flatMap(
                person -> people.findBySheetIdAndNameKey(plan.getTargetSheetId(), Person.keyOf(person.getName())));
    }
}
