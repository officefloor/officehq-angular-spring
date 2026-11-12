package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the plans of a sheet's people. A plan has an amount (more than 0.00), a start date, an interval of every so
 * many days (at least 1) or, run monthly, a day of the month (1 to 31), and an end date not before its start. Generate makes, for every plan on the sheet in the order
 * they were added, a DRAFT claim of its amount with an empty note for each plan date (the start, then every so many
 * days) up to its end and the sheet's today that it has made none for yet, each added as the claim form adds one: a
 * date the claim form refuses is skipped, and tried again by the next generate. A plan may name one of the sheet's
 * descendants: its claims are then made on that sheet, for the person there of the same name (none when there is no
 * such person), as that sheet's claim form adds them; the plan still counts what it made.
 */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PlanDateRepository dates;
    private final PlanPauseRepository pauses;
    private final PlanChangeRepository changes;
    private final PersonRepository people;
    private final ClaimService claims;
    private final SheetService sheets;
    private final Audit audit;
    /** Each plan date's claim is made in its own transaction, so one refused rolls back only itself. */
    private final TransactionTemplate each;

    public PlanService(PlanRepository plans, PlanDateRepository dates, PlanPauseRepository pauses,
            PlanChangeRepository changes, PersonRepository people, ClaimService claims,
            SheetService sheets, Audit audit, PlatformTransactionManager transactions) {
        this.plans = plans;
        this.dates = dates;
        this.pauses = pauses;
        this.changes = changes;
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.audit = audit;
        this.each = new TransactionTemplate(transactions);
        this.each.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** The sheet's plans in the order they were added. */
    public List<Plan> list(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** How many claims each of the sheet's plans has made, by plan id. */
    public Map<Long, Long> generatedCounts(String sheetId) {
        return dates.findBySheetId(sheetId).stream()
                .collect(Collectors.groupingBy(PlanDate::getPlanId, Collectors.counting()));
    }

    /** The sheet's pauses of each plan, by plan id. */
    private Map<Long, List<PlanPause>> pausesByPlan(String sheetId) {
        return pauses.findBySheetId(sheetId).stream().collect(Collectors.groupingBy(PlanPause::getPlanId));
    }

    private static boolean skipped(List<PlanPause> pauses, LocalDate date) {
        return pauses.stream().anyMatch(pause -> pause.skips(date));
    }

    /** The ids of the sheet's plans now paused. */
    public Set<Long> paused(String sheetId) {
        return pauses.findBySheetId(sheetId).stream().filter(PlanPause::isOpen).map(PlanPause::getPlanId)
                .collect(Collectors.toSet());
    }

    /** The sheet's changes of each plan's amount, oldest first, by plan id. */
    public Map<Long, List<PlanChange>> changes(String sheetId) {
        return changes.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PlanChange::getPlanId));
    }

    /** The plan's changes of its amount, oldest first. */
    public List<PlanChange> changes(String sheetId, Plan plan) {
        return changes.findByPlanIdAndSheetIdOrderByIdAsc(plan.getId(), sheetId);
    }

    /** The plan's first five dates after the sheet's today, up to its end, those paused left out. */
    public List<LocalDate> nextDates(String sheetId, Plan plan) {
        List<PlanPause> its = pauses.findByPlanIdAndSheetId(plan.getId(), sheetId);
        return plan.datesAfter(sheets.today(sheetId), 5, date -> skipped(its, date));
    }

    /** The first five dates after the sheet's today of each of the sheet's plans, by plan id. */
    public Map<Long, List<LocalDate>> nextDates(String sheetId) {
        List<Plan> all = list(sheetId);
        if (all.isEmpty()) {
            return Map.of();
        }
        LocalDate today = sheets.today(sheetId);
        Map<Long, List<PlanPause>> paused = pausesByPlan(sheetId);
        return all.stream().collect(Collectors.toMap(Plan::getId, plan -> {
            List<PlanPause> its = paused.getOrDefault(plan.getId(), List.of());
            return plan.datesAfter(today, 5, date -> skipped(its, date));
        }));
    }

    @Transactional
    public Plan add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end, String targetSheetId) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        if (amount.signum() <= 0) {
            throw new PlanRefusedException("A plan's amount must be more than 0.00");
        }
        if ((every == null) == (day == null)) {
            throw new PlanRefusedException("A plan repeats either every so many days or on a day of the month");
        }
        if (every != null && every < 1) {
            throw new PlanRefusedException("A plan repeats every 1 day or more");
        }
        if (day != null && (day < 1 || day > 31)) {
            throw new PlanRefusedException("A plan's day of the month is 1 to 31");
        }
        if (end.isBefore(start)) {
            throw new PlanRefusedException("A plan's end may not be before its start");
        }
        if (targetSheetId != null && !sheets.descendantsOf(sheetId).contains(targetSheetId)) {
            throw new PlanRefusedException("A plan's sheet must be one of this sheet's descendants");
        }
        Plan saved = plans.saveAndFlush(new Plan(sheetId, personId, amount, start, every, day, end, targetSheetId));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + " start=" + start + (day != null ? " day=" + day : " every=" + every) + " end=" + end
                + (targetSheetId != null ? " sheet=" + targetSheetId : ""));
        return saved;
    }

    /**
     * Pause the plan from the sheet's today, or resume it on the sheet's today: a plan date on or after the day it was
     * paused and before the day it was resumed is skipped. Pausing a paused plan or resuming a running one is refused.
     */
    @Transactional
    public Plan pause(String sheetId, String user, Long planId, boolean on) {
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new PlanNotFoundException(sheetId, planId));
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, plan.getPersonId()));
        LocalDate today = sheets.today(sheetId);
        PlanPause open = pauses.findByPlanIdAndSheetId(planId, sheetId).stream().filter(PlanPause::isOpen)
                .findFirst().orElse(null);
        if (on) {
            if (open != null) {
                throw new PlanRefusedException("The plan is already paused");
            }
            pauses.saveAndFlush(new PlanPause(sheetId, planId, today));
        } else {
            if (open == null) {
                throw new PlanRefusedException("The plan is not paused");
            }
            open.resume(today);
            pauses.saveAndFlush(open);
        }
        audit.recordOnCommit((on ? "PLAN_PAUSED" : "PLAN_RESUMED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " start=" + plan.getStart());
        return plan;
    }

    /**
     * Change the plan's amount on the sheet's today: the claims generated from then on are of the new amount, and those
     * already generated that are still DRAFT are changed to it, each as the claim form would edit it (one it refuses
     * keeps its amount); those in any other status keep theirs. An amount not more than 0.00 is refused.
     */
    public Plan changeAmount(String sheetId, String user, Long planId, BigDecimal amount) {
        Plan plan = each.execute(status -> change(sheetId, user, planId, amount));
        String on = plan.getTargetSheetId() != null ? plan.getTargetSheetId() : sheetId;
        for (PlanDate made : dates.findByPlanIdAndSheetIdOrderByIdAsc(planId, sheetId)) {
            if (made.getClaimId() != null) {
                try {
                    each.executeWithoutResult(status -> claims.editDraftAmount(on, user, made.getClaimId(), amount));
                } catch (RuntimeException e) {
                    // The claim form refuses the edit: the claim keeps its amount.
                }
            }
        }
        return plan;
    }

    private Plan change(String sheetId, String user, Long planId, BigDecimal amount) {
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new PlanNotFoundException(sheetId, planId));
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, plan.getPersonId()));
        if (amount.signum() <= 0) {
            throw new PlanRefusedException("A plan's amount must be more than 0.00");
        }
        plan.changeAmount(amount);
        plans.saveAndFlush(plan);
        changes.saveAndFlush(new PlanChange(sheetId, planId, sheets.today(sheetId), amount));
        audit.recordOnCommit("PLAN_CHANGED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " start=" + plan.getStart() + " amount=" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        return plan;
    }

    /** Make the claims of every plan date due and not yet made: how many were made (none for a viewer). */
    public int generate(String sheetId, String user) {
        if (sheets.isViewer(sheetId, user)) {
            return 0;
        }
        LocalDate today = sheets.today(sheetId);
        Map<Long, Set<LocalDate>> made = dates.findBySheetId(sheetId).stream()
                .collect(Collectors.groupingBy(PlanDate::getPlanId,
                        Collectors.mapping(PlanDate::getDate, Collectors.toSet())));
        Map<Long, List<PlanPause>> paused = pausesByPlan(sheetId);
        int count = 0;
        for (Plan plan : list(sheetId)) {
            Long personId = claimant(sheetId, plan);
            if (personId == null) {
                continue;
            }
            Set<LocalDate> done = made.getOrDefault(plan.getId(), Set.of());
            List<PlanPause> its = paused.getOrDefault(plan.getId(), List.of());
            for (LocalDate date : plan.datesUpTo(today)) {
                if (!done.contains(date) && !skipped(its, date) && make(sheetId, user, plan, personId, date)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * The person the plan's claims are made for: the plan's own person, or, for a plan on a descendant sheet, the person
     * of the same name there (null when there is none).
     */
    private Long claimant(String sheetId, Plan plan) {
        if (plan.getTargetSheetId() == null) {
            return plan.getPersonId();
        }
        return people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                .flatMap(person -> people.findBySheetIdAndNameKey(plan.getTargetSheetId(), Person.key(person.getName())))
                .map(Person::getId).orElse(null);
    }

    /**
     * Make the plan's claim for the person on the date as the claim form of the plan's sheet (its own or the descendant
     * it names) would: false when it refuses it (nothing is kept).
     */
    private boolean make(String sheetId, String user, Plan plan, Long personId, LocalDate date) {
        String on = plan.getTargetSheetId() != null ? plan.getTargetSheetId() : sheetId;
        try {
            each.executeWithoutResult(status -> {
                Claim claim = claims.add(on, user, personId, plan.getAmount(), date, "", null);
                dates.saveAndFlush(new PlanDate(sheetId, plan.getId(), date, claim.getId()));
            });
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
