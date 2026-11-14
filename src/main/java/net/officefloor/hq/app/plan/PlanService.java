package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The plans on a sheet, and the generate that makes their claims. */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PlanClaimRepository made;
    private final PlanPauseRepository pauses;
    private final PlanChangeRepository changes;
    private final PersonRepository people;
    private final ClaimService claims;
    private final SheetService sheets;
    private final SettingService settings;
    private final Audit audit;

    public PlanService(PlanRepository plans, PlanClaimRepository made, PlanPauseRepository pauses,
            PlanChangeRepository changes, PersonRepository people, ClaimService claims, SheetService sheets, SettingService settings, Audit audit) {
        this.plans = plans;
        this.made = made;
        this.pauses = pauses;
        this.changes = changes;
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's plans in the order they were added: as they stand now, or those added by the end
     *  of the as-of date. */
    public List<Plan> list(String sheetId, LocalDate asOf) {
        return plans.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> asOf == null || !p.getMadeOn().isAfter(asOf))
                .toList();
    }

    /** How many claims each plan has made, by plan id: so far, or by the end of the as-of date. */
    public Map<Long, Integer> generated(List<Plan> them, LocalDate asOf) {
        Map<Long, Integer> counts = new HashMap<>();
        for (PlanClaim c : made.findByPlanIdInOrderByIdAsc(them.stream().map(Plan::getId).toList())) {
            if (asOf == null || !c.getMadeOn().isAfter(asOf)) {
                counts.merge(c.getPlanId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Each plan's pauses, by plan id: as they stand now, or as they stood at the end of the as-of
     *  date (a pause made after it left out, one resumed after it still open). */
    public Map<Long, List<PlanPause>> pauses(List<Plan> them, LocalDate asOf) {
        Map<Long, List<PlanPause>> byPlan = new HashMap<>();
        for (PlanPause p : pauses.findByPlanIdInOrderByIdAsc(them.stream().map(Plan::getId).toList())) {
            if (asOf != null && p.getPausedOn().isAfter(asOf)) {
                continue;
            }
            PlanPause shown = p;
            if (asOf != null && p.getResumedOn() != null && p.getResumedOn().isAfter(asOf)) {
                shown = new PlanPause(p.getPlanId(), p.getPausedOn());
            }
            byPlan.computeIfAbsent(p.getPlanId(), k -> new ArrayList<>()).add(shown);
        }
        return byPlan;
    }

    /** Each plan's amount changes, oldest first, by plan id: so far, or those made by the end of the
     *  as-of date. */
    public Map<Long, List<PlanChange>> changes(List<Plan> them, LocalDate asOf) {
        Map<Long, List<PlanChange>> byPlan = new HashMap<>();
        for (PlanChange c : changes.findByPlanIdInOrderByIdAsc(them.stream().map(Plan::getId).toList())) {
            if (asOf == null || !c.getChangedOn().isAfter(asOf)) {
                byPlan.computeIfAbsent(c.getPlanId(), k -> new ArrayList<>()).add(c);
            }
        }
        return byPlan;
    }

    /** The plan's amount after its changes: that of the latest, or the one it was added with. */
    public static BigDecimal amountAfter(Plan plan, List<PlanChange> them) {
        return them.isEmpty() ? plan.getAmount() : them.get(them.size() - 1).getAmount();
    }

    /** Change the plan's amount on the sheet's today: an amount of 0.00 or more with up to two decimals.
     *  The claims it generates from then on take it; those it made already keep theirs. */
    @Transactional
    public Plan changeAmount(String sheetId, String user, Long planId, String amount) {
        sheets.lock(sheetId);
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new NoSuchElementException("No plan " + planId + " on sheet " + sheetId));
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId).orElseThrow();
        BigDecimal figure = amountOf(amount);
        changes.saveAndFlush(new PlanChange(planId, sheets.today(sheetId), figure));
        audit.recordOnCommit("PLAN_CHANGED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " start=" + plan.getStart() + " amount=" + figure.toPlainString());
        return plan;
    }

    /** Whether the plan date falls in one of the pauses, and so is skipped. */
    public static boolean skipped(List<PlanPause> them, LocalDate date) {
        return them.stream().anyMatch(p -> p.skips(date));
    }

    /** Pause the plan from the sheet's today, or resume it on the sheet's today; pausing a paused plan or
     *  resuming a running one is refused. */
    @Transactional
    public Plan pause(String sheetId, String user, Long planId, boolean on) {
        sheets.lock(sheetId);
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new NoSuchElementException("No plan " + planId + " on sheet " + sheetId));
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId).orElseThrow();
        LocalDate today = sheets.today(sheetId);
        Optional<PlanPause> open = pauses.findFirstByPlanIdAndResumedOnIsNull(planId);
        if (on) {
            if (open.isPresent()) {
                throw new PlanRefusedException("The plan is paused already");
            }
            pauses.saveAndFlush(new PlanPause(planId, today));
        } else {
            PlanPause pause = open.orElseThrow(() -> new PlanRefusedException("The plan is not paused"));
            pause.resume(today);
            pauses.saveAndFlush(pause);
        }
        audit.recordOnCommit((on ? "PLAN_PAUSED" : "PLAN_RESUMED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " start=" + plan.getStart());
        return plan;
    }

    /** Add a plan for a person: an amount of 0.00 or more with up to two decimals, a start (blank: the
     *  sheet's today), either every so many days (a whole number, at least 1) or monthly on a day of the
     *  month (1 to 31), and an end on or after the start. */
    @Transactional
    public Plan add(String sheetId, String user, Long personId, String amount, LocalDate start, String every,
            String day, LocalDate end) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + personId + " on sheet " + sheetId));
        BigDecimal figure = amountOf(amount);
        boolean monthly = day != null && !day.isBlank();
        if (monthly && every != null && !every.isBlank()) {
            throw new PlanRefusedException("A plan runs either every so many days or on a day of the month");
        }
        Integer days = monthly ? null : everyOf(every);
        Integer dayOfMonth = monthly ? dayOf(day) : null;
        LocalDate today = sheets.today(sheetId);
        LocalDate from = start != null ? start : today;
        if (end == null || end.isBefore(from)) {
            throw new PlanRefusedException("A plan must end on or after its start");
        }
        Plan saved = plans.saveAndFlush(new Plan(sheetId, personId, figure, from, days, dayOfMonth, end, today));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + saved.getAmount().toPlainString() + " start=" + saved.getStart()
                + (monthly ? " day=" + saved.getDay() : " every=" + saved.getEvery()) + " end=" + saved.getEnd());
        return saved;
    }

    /** For every plan on the sheet, in the order they were added, add a DRAFT claim of its amount (that of its latest change, if any) with
     *  an empty note for each plan date up to its end and the sheet's today it has made none for yet,
     *  as the claim form would, a date a pause skips left out; a date the form refuses is passed over, to be tried again next time.
     *  The claims made, in the order they were; a viewer makes none. */
    @Transactional
    public List<Claim> generate(String sheetId, String user) {
        if (settings.isViewer(sheetId, user)) {
            return List.of();
        }
        sheets.lock(sheetId);
        LocalDate today = sheets.today(sheetId);
        List<Plan> them = plans.findBySheetIdOrderByIdAsc(sheetId);
        Set<String> done = new HashSet<>();
        for (PlanClaim c : made.findByPlanIdInOrderByIdAsc(them.stream().map(Plan::getId).toList())) {
            done.add(c.getPlanId() + "@" + c.getDate());
        }
        Map<Long, List<PlanPause>> paused = pauses(them, null);
        Map<Long, List<PlanChange>> changed = changes(them, null);
        List<Claim> added = new ArrayList<>();
        for (Plan plan : them) {
            List<PlanPause> its = paused.getOrDefault(plan.getId(), List.of());
            BigDecimal amount = amountAfter(plan, changed.getOrDefault(plan.getId(), List.of()));
            for (LocalDate date : plan.datesUpTo(today, d -> skipped(its, d))) {
                if (done.contains(plan.getId() + "@" + date)
                        || !claims.accepts(sheetId, plan.getPersonId(), date)) {
                    continue;
                }
                Claim claim = claims.add(sheetId, user, plan.getPersonId(), amount, date, "", null);
                made.saveAndFlush(new PlanClaim(plan.getId(), date, claim.getId(), today));
                added.add(claim);
            }
        }
        return added;
    }

    private static BigDecimal amountOf(String amount) {
        BigDecimal figure;
        try {
            figure = new BigDecimal(amount == null ? "" : amount.trim());
        } catch (NumberFormatException e) {
            throw new PlanRefusedException("The amount is not a number");
        }
        if (figure.signum() < 0 || figure.scale() > 2 || figure.precision() - figure.scale() > 10) {
            throw new PlanRefusedException("The amount must be 0.00 or more, with up to two decimals");
        }
        return figure.setScale(2);
    }

    private static int everyOf(String every) {
        String typed = every == null ? "" : every.trim();
        if (!typed.matches("\\d{1,6}") || Integer.parseInt(typed) < 1) {
            throw new PlanRefusedException("A plan runs every whole number of days, at least 1");
        }
        return Integer.parseInt(typed);
    }

    private static int dayOf(String day) {
        String typed = day.trim();
        if (!typed.matches("\\d{1,2}") || Integer.parseInt(typed) < 1 || Integer.parseInt(typed) > 31) {
            throw new PlanRefusedException("A monthly plan runs on a day of the month, 1 to 31");
        }
        return Integer.parseInt(typed);
    }
}
