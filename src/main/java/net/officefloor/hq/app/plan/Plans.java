package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
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
 * the plan's amount for each plan date (the start, then every N days; or that day of every month from the start's month, on the month's
 * last day when it is shorter, leaving out dates before the start) up to its end and the sheet's today that has
 * none yet, dated that day with an empty note, by every rule of the claim form. A date the form refuses is skipped,
 * writing nothing, and tried again the next time. A plan date within one of the plan's pauses (on or after the day
 * it was paused and before the day it was resumed) is skipped, not postponed: no claim is ever made for it. A viewer's generate makes nothing: every claim it would make is
 * refused. A plan naming one of the sheet's descendants makes its claims on that sheet instead, for the person there of
 * the same name as the plan's (none while there is no such person), by that sheet's rules and in that sheet's audit.
 */
@Service
public class Plans {

    private final PlanRepository plans;
    private final PlanClaimRepository made;
    private final PlanPauseRepository pauses;
    private final PlanChangeRepository changes;
    private final PersonRepository people;
    private final ClaimService claims;
    private final ClaimRepository claimRows;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final AccessService access;

    public Plans(PlanRepository plans, PlanClaimRepository made, PlanPauseRepository pauses,
            PlanChangeRepository changes, PersonRepository people, ClaimService claims, ClaimRepository claimRows,
            SheetService sheets, SheetRepository sheetRows, ApplicationEventPublisher events,
            PlatformTransactionManager transactions, AccessService access) {
        this.access = access;
        this.plans = plans;
        this.made = made;
        this.pauses = pauses;
        this.changes = changes;
        this.people = people;
        this.claims = claims;
        this.claimRows = claimRows;
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

    /** The plan's pauses in the order they were made. */
    @Transactional(readOnly = true)
    public List<PlanPause> pauses(Plan plan) {
        return pauses.findByPlanIdOrderByIdAsc(plan.getId());
    }

    /** The plan's amount changes in the order they were made. */
    @Transactional(readOnly = true)
    public List<PlanChange> changes(Plan plan) {
        return changes.findByPlanIdOrderByIdAsc(plan.getId());
    }

    /**
     * Change the plan's amount on the sheet's today: the claims generated from then on are made for it, and those
     * already generated that are still DRAFT are edited to it, each as the claim form edits a claim's amount and in
     * its own change (one the form refuses keeps its amount); those in any other status keep theirs.
     */
    public void changeAmount(String sheetId, String user, long planId, BigDecimal amount) {
        Plan plan = transaction.execute(status -> {
            sheets.load(sheetId, user);
            sheetRows.lockById(sheetId);
            Plan changed = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan"));
            changed.changeAmount(amount);
            plans.saveAndFlush(changed);
            changes.saveAndFlush(new PlanChange(planId, sheets.today(sheetId), amount));
            String name = people.findByIdAndSheetId(changed.getPersonId(), sheetId).map(Person::getName).orElse("");
            events.publishEvent(new PlanChanged(sheetId, user, name, changed.getStart(), amount));
            return changed;
        });
        String target = plan.getTargetSheetId() == null ? sheetId : plan.getTargetSheetId();
        for (PlanClaim claim : made.findByPlanIdOrderByIdAsc(planId)) {
            if (claim.getClaimId() != null) {
                editDraft(target, user, claim.getClaimId(), amount);
            }
        }
    }

    /** Edit the plan's claim on the sheet to the amount in its own change, when it is still a DRAFT. */
    private void editDraft(String sheetId, String user, long claimId, BigDecimal amount) {
        try {
            transaction.executeWithoutResult(status -> {
                sheetRows.lockById(sheetId);
                claimRows.findByIdAndSheetId(claimId, sheetId)
                        .filter(c -> c.getStatus() == ClaimStatus.DRAFT)
                        .ifPresent(c -> claims.editAmount(sheetId, user, claimId, amount));
            });
        } catch (RuntimeException refused) {
            // refused by the claim form's rules: nothing written, the claim keeps its amount
        }
    }

    /**
     * Pause the plan on the sheet's today, or resume it on the sheet's today; refused when pausing a paused plan or
     * resuming a running one.
     */
    @Transactional
    public void pause(String sheetId, String user, long planId, boolean resume) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan"));
        Optional<PlanPause> running = pauses.findByPlanIdAndResumedOnIsNull(planId);
        LocalDate today = sheets.today(sheetId);
        if (resume) {
            PlanPause pause = running.orElseThrow(() -> new PlanRefused("The plan is not paused."));
            pause.resume(today);
            pauses.saveAndFlush(pause);
        } else {
            if (running.isPresent()) {
                throw new PlanRefused("The plan is already paused.");
            }
            pauses.saveAndFlush(new PlanPause(planId, today));
        }
        String name = people.findByIdAndSheetId(plan.getPersonId(), sheetId).map(Person::getName).orElse("");
        events.publishEvent(new PlanPaused(sheetId, user, name, plan.getStart(), resume));
    }

    /**
     * Add a plan to the person, running every N days or monthly on a day of the month (exactly one given); refused
     * when its end is before its start, its interval is under one day, its day is not 1 to 31 or the sheet it names
     * (blank: none) is not one of the sheet's descendants.
     */
    @Transactional
    public void add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end, String targetSheet) {
        if ((every == null) == (day == null)) {
            throw new PlanRefused("Enter either every how many days or a day of the month.");
        }
        if (every != null && every < 1) {
            throw new PlanRefused("Enter a whole number of days, at least 1.");
        }
        if (day != null && (day < 1 || day > 31)) {
            throw new PlanRefused("Enter a day of the month from 1 to 31.");
        }
        if (end.isBefore(start)) {
            throw new PlanRefused("The end cannot be before the start.");
        }
        String target = targetSheet == null || targetSheet.isBlank() ? null : targetSheet.trim();
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (target != null && !sheets.descendants(sheetId).contains(target)) {
            throw new PlanRefused("The sheet " + target + " is not a descendant of this sheet.");
        }
        plans.saveAndFlush(new Plan(sheetId, personId, amount, start, every, day, end, target));
        events.publishEvent(new PlanAdded(sheetId, user, person.getName(), amount, start, every, day, end, target));
    }

    /** Make the claims the plans are due up to the sheet's today; returns how many were made. */
    public int generate(String sheetId, String user) {
        sheets.load(sheetId, user);
        if (access.isViewer(sheetId, user)) {
            return 0;
        }
        LocalDate today = sheets.today(sheetId);
        int count = 0;
        for (Plan plan : list(sheetId)) {
            LocalDate last = plan.getEnd().isBefore(today) ? plan.getEnd() : today;
            List<PlanPause> skipped = pauses(plan);
            String target = plan.getTargetSheetId() == null ? sheetId : plan.getTargetSheetId();
            Long personId = plan.getTargetSheetId() == null ? plan.getPersonId() : namesake(sheetId, plan, target);
            if (personId == null) {
                continue; // no person of the plan's name on the sheet it names: nothing to make
            }
            for (LocalDate on : plan.datesUpTo(last)) {
                if (skipped.stream().anyMatch(p -> p.skips(on))) {
                    continue;
                }
                if (makeClaim(target, user, plan, personId, on)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** The id of the person on the target sheet of the same name as the plan's person, or null when there is none. */
    private Long namesake(String sheetId, Plan plan, String target) {
        return people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                .flatMap(p -> people.findBySheetIdOrderByPositionAscIdAsc(target).stream()
                        .filter(t -> t.getName().trim().equalsIgnoreCase(p.getName().trim()))
                        .map(Person::getId)
                        .findFirst())
                .orElse(null);
    }

    /**
     * Make the plan's claim on the date for the person on the sheet, each in its own change: false when it has one or
     * the form refuses it.
     */
    private boolean makeClaim(String sheetId, String user, Plan plan, long personId, LocalDate on) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                sheetRows.lockById(sheetId);
                if (made.existsByPlanIdAndOn(plan.getId(), on)) {
                    return false;
                }
                long claimId = claims.addClaim(sheetId, user, personId, plan.getAmount(), on, "", null, null, null)
                        .getId();
                made.saveAndFlush(new PlanClaim(plan.getId(), on, claimId));
                return true;
            }));
        } catch (RuntimeException refused) {
            return false; // refused by the claim form's rules: nothing written, tried again next time
        }
    }
}
