package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The plans on a sheet, and the DRAFT claims generating them makes. */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PlanClaimRepository made;
    private final PlanPauseRepository pauses;
    private final PlanChangeRepository changes;
    private final PersonRepository people;
    private final ClaimService claims;
    private final ClaimRepository claimStore;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final RoleService roles;
    private final Audit audit;

    public PlanService(PlanRepository plans, PlanClaimRepository made, PlanPauseRepository pauses,
            PlanChangeRepository changes, PersonRepository people, ClaimService claims,
            ClaimRepository claimStore, SheetService sheets, SheetRepository sheetStore, RoleService roles, Audit audit) {
        this.plans = plans;
        this.made = made;
        this.pauses = pauses;
        this.changes = changes;
        this.people = people;
        this.claims = claims;
        this.claimStore = claimStore;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.roles = roles;
        this.audit = audit;
    }

    /** How many next dates a plan shows. */
    private static final int NEXT_DATES = 5;

    /** A plan with how many claims it has made so far, its next dates after the sheet's today, and whether it is
     *  paused now, and the changes of its amount, oldest first. */
    public record Counted(Plan plan, int generated, List<LocalDate> nextDates, boolean paused,
            List<PlanChange> changes) {
    }

    /** What a generate did: the claims it made, in the order made, and every plan on the sheet as it is now. */
    public record Generated(List<Claim> claims, List<Counted> plans) {
    }

    /** What a change of a plan's amount did: the plan as it is now, and the DRAFT claims it generated that took the
     *  new amount, in the order they were made. */
    public record Changed(Counted plan, List<Claim> edited) {
    }

    /** The sheet's plans in the order they were added, each with how many claims it has made. */
    @Transactional(readOnly = true)
    public List<Counted> list(String sheetId) {
        List<Plan> all = plans.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Long> counts = made.findByPlanIdInOrderByIdAsc(all.stream().map(Plan::getId).toList()).stream()
                .collect(Collectors.groupingBy(PlanClaim::getPlanId, Collectors.counting()));
        Map<Long, List<PlanPause>> paused = pausesOf(all);
        Map<Long, List<PlanChange>> changed = changes.findByPlanIdInOrderByIdAsc(all.stream().map(Plan::getId).toList())
                .stream().collect(Collectors.groupingBy(PlanChange::getPlanId));
        LocalDate today = sheetStore.findById(sheetId).map(Sheet::getToday).orElse(null);
        return all.stream().map(p -> counted(p, counts.getOrDefault(p.getId(), 0L).intValue(), today,
                paused.getOrDefault(p.getId(), List.of()), changed.getOrDefault(p.getId(), List.of()))).toList();
    }

    private static Counted counted(Plan plan, int generated, LocalDate today, List<PlanPause> paused,
            List<PlanChange> changed) {
        return new Counted(plan, generated,
                today == null ? List.of() : plan.nextDates(today, NEXT_DATES, d -> skips(paused, d)),
                paused.stream().anyMatch(PlanPause::isOpen), changed);
    }

    /** Each plan's pauses, by plan. */
    private Map<Long, List<PlanPause>> pausesOf(List<Plan> all) {
        return pauses.findByPlanIdInOrderByIdAsc(all.stream().map(Plan::getId).toList()).stream()
                .collect(Collectors.groupingBy(PlanPause::getPlanId));
    }

    /** Whether one of a plan's pauses skips the date. */
    private static boolean skips(List<PlanPause> paused, LocalDate date) {
        return paused.stream().anyMatch(pp -> pp.skips(date));
    }

    /** Pause a running plan (on) from the sheet's today, or resume a paused one (off) from it; a plan date on or after
     *  the day it was paused and before the day it was resumed is skipped. Refused when pausing a paused plan or
     *  resuming a running one. */
    @Transactional
    public Counted pause(String sheetId, String user, long planId, boolean on) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan on this sheet"));
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId).orElseThrow();
        List<PlanPause> theirs = pauses.findByPlanIdInOrderByIdAsc(List.of(planId));
        PlanPause open = theirs.stream().filter(PlanPause::isOpen).findFirst().orElse(null);
        if (on) {
            if (open != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The plan is already paused");
            }
            pauses.saveAndFlush(new PlanPause(planId, sheet.getToday()));
        } else {
            if (open == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The plan is not paused");
            }
            open.resume(sheet.getToday());
            pauses.flush();
        }
        audit.recordChangeOnCommit(sheetId, user, (on ? "PLAN_PAUSED" : "PLAN_RESUMED") + " sheet=" + sheetId
                + " by=" + user + " name=" + person.getName() + " start=" + plan.getStart());
        int generated = made.findByPlanIdInOrderByIdAsc(List.of(planId)).size();
        return counted(plan, generated, sheet.getToday(), pauses.findByPlanIdInOrderByIdAsc(List.of(planId)),
                changes.findByPlanIdInOrderByIdAsc(List.of(planId)));
    }

    /** Change the plan's amount from the sheet's today: the claims generated from then on take the new amount, and
     *  so do those it already generated that are still DRAFT, each edited as the claim form would edit it (on the
     *  sheet it was made on); those in any other status keep theirs. Refused when one of those edits is refused, or when the amount is one the plan form refuses (negative, or with more
     *  than two decimals). */
    @Transactional
    public Changed changeAmount(String sheetId, String user, long planId, BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's amount is not negative, with up to two decimals");
        }
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan on this sheet"));
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId).orElseThrow();
        BigDecimal now = amount.setScale(2, RoundingMode.UNNECESSARY);
        plan.changeAmount(now);
        plans.flush();
        changes.saveAndFlush(new PlanChange(planId, sheet.getToday(), now));
        audit.recordChangeOnCommit(sheetId, user, "PLAN_CHANGED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " start=" + plan.getStart() + " amount=" + now.toPlainString());
        List<PlanClaim> its = made.findByPlanIdInOrderByIdAsc(List.of(planId));
        List<Claim> edited = new ArrayList<>();
        for (PlanClaim pc : its) {
            Claim draft = claimStore.findById(pc.getClaimId()).filter(c -> c.getStatus() == ClaimStatus.DRAFT)
                    .orElse(null);
            if (draft != null) {
                edited.add(claims.update(draft.getSheetId(), user, draft.getId(), now, draft.getDate(),
                        draft.getNote(), draft.getCorrects(), draft.getRef(), draft.getTags()));
            }
        }
        int generated = its.size();
        return new Changed(counted(plan, generated, sheet.getToday(), pauses.findByPlanIdInOrderByIdAsc(List.of(planId)),
                changes.findByPlanIdInOrderByIdAsc(List.of(planId))), edited);
    }

    /** Add a plan for a person; a missing start takes the sheet's today. It runs either every so many days or
     *  monthly on a day of the month, not both. Refused when the amount is one the claim form refuses for a claim
     *  that corrects none (negative, or with more than two decimals), when the interval is not a whole number of days
     *  of at least 1, when the day of the month is not 1 to 31, when the end is before the start, or when it names a
     *  sheet (a blank one is none) that is not one of the sheet's descendants. */
    @Transactional
    public Counted add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end, String target) {
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's amount is not negative, with up to two decimals");
        }
        if ((every == null) == (day == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan repeats every so many days or on a day of the month");
        }
        if (every != null && every < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan repeats every 1 day or more");
        }
        if (day != null && (day < 1 || day > 31)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's day of the month is 1 to 31");
        }
        if (end == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan has an end date");
        }
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        LocalDate from = start != null ? start : sheet.getToday();
        if (end.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's end is not before its start");
        }
        String onSheet = target == null || target.isBlank() ? null : target.trim();
        if (onSheet != null && !sheetStore.descendantsOf(sheetId).contains(onSheet)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's sheet is one of this sheet's descendants");
        }
        Plan plan = plans.saveAndFlush(new Plan(sheetId, personId, amount.setScale(2, RoundingMode.UNNECESSARY), from,
                every, day, end, onSheet));
        audit.recordChangeOnCommit(sheetId, user, "PLAN_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " amount=" + plan.getAmount().toPlainString()
                + " start=" + plan.getStart()
                + (plan.getDay() != null ? " day=" + plan.getDay() : " every=" + plan.getEvery())
                + " end=" + plan.getEnd()
                + (plan.getTargetSheetId() == null ? "" : " sheet=" + plan.getTargetSheetId()));
        return counted(plan, 0, sheet.getToday(), List.of(), List.of());
    }

    /**
     * Make, for every plan on the sheet in the order they were added, a DRAFT claim with the plan's amount for each
     * plan date (its start, then every so many days; or its day in each month from the start's) up to its end and the
     * sheet's today that it has made none for yet and no pause of it skips, dated that day with an empty note, by the
     * claim form's rules: a date the form refuses is skipped, and tried again the next time. A viewer, who can change nothing, has every date
     * refused, so makes none. A plan that names a descendant sheet makes its claims there instead, for the person of
     * the same name on it (none when there is none), by that sheet's rules and written in its audit.
     */
    @Transactional
    public Generated generate(String sheetId, String user) {
        sheets.load(sheetId, user);
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        if (roles.isViewer(sheetId, user.trim())) {
            return new Generated(List.of(), list(sheetId));
        }
        List<Plan> all = plans.findBySheetIdOrderByIdAsc(sheetId);
        Set<String> done = new HashSet<>();
        for (PlanClaim pc : made.findByPlanIdInOrderByIdAsc(all.stream().map(Plan::getId).toList())) {
            done.add(pc.getPlanId() + "@" + pc.getDate());
        }
        Map<Long, List<PlanPause>> paused = pausesOf(all);
        List<Claim> added = new ArrayList<>();
        for (Plan plan : all) {
            String onSheet = plan.getTargetSheetId() == null ? sheetId : plan.getTargetSheetId();
            Long personId = plan.getPersonId();
            if (!onSheet.equals(sheetId)) {
                String name = people.findByIdAndSheetId(plan.getPersonId(), sheetId).map(Person::getName).orElse(null);
                personId = name == null || sheetStore.findById(onSheet).isEmpty() || roles.isViewer(onSheet, user.trim())
                        ? null
                        : people.findBySheetIdAndNameIgnoreCase(onSheet, name).map(Person::getId).orElse(null);
                if (personId == null) {
                    continue;
                }
            }
            List<PlanPause> its = paused.getOrDefault(plan.getId(), List.of());
            LocalDate last = plan.getEnd().isBefore(today) ? plan.getEnd() : today;
            for (LocalDate d : plan.datesUpTo(last)) {
                if (done.contains(plan.getId() + "@" + d) || skips(its, d) || !claims.accepts(onSheet, personId, d)) {
                    continue;
                }
                Claim claim = claims.add(onSheet, user, personId, plan.getAmount(), d, "", null, null);
                made.saveAndFlush(new PlanClaim(plan.getId(), d, claim.getId()));
                added.add(claim);
            }
        }
        return new Generated(added, list(sheetId));
    }

    /** Hand the person's plans to another, as merging them into that person does. */
    @Transactional
    public void reassign(long fromPersonId, long toPersonId) {
        for (Plan plan : plans.findByPersonIdOrderByIdAsc(fromPersonId)) {
            plan.reassign(toPersonId);
        }
        plans.flush();
    }

    /** Remove the person's plans, as the person leaving the sheet does. */
    @Transactional
    public void removeOf(long personId) {
        List<Plan> theirs = plans.findByPersonIdOrderByIdAsc(personId);
        made.deleteAll(made.findByPlanIdInOrderByIdAsc(theirs.stream().map(Plan::getId).toList()));
        made.flush();
        pauses.deleteAll(pauses.findByPlanIdInOrderByIdAsc(theirs.stream().map(Plan::getId).toList()));
        pauses.flush();
        changes.deleteAll(changes.findByPlanIdInOrderByIdAsc(theirs.stream().map(Plan::getId).toList()));
        changes.flush();
        plans.deleteAll(theirs);
        plans.flush();
    }
}
