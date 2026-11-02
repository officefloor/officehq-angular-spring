package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.CorrectionRefusedException;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The people's plans: an amount from a start date every so many days (at least 1), or monthly on a
 * day of the month (1 to 31), one or the other, up to an end date on or after it. Generating makes, for every plan on the sheet in the order they were added, a DRAFT
 * claim with the plan's amount and an empty note for each plan date up to its end and the sheet's
 * today that it has made none for yet, dated that day and added by every rule of the claim form. A
 * date the form refuses is skipped, and tried again by the next generate. Each claim is made in its
 * own transaction, so one refused leaves the others made. A plan can be paused and resumed (each on the
 * sheet's today): a plan date on or after the day it was paused and before the day it was resumed is
 * skipped, not postponed, so generate makes nothing for it and its next dates leave it out.
 */
@Service
public class ClaimPlanService {

    private final ClaimPlanRepository plans;
    private final ClaimPlanDateRepository dates;
    private final ClaimPlanPauseRepository pauses;
    private final PersonRepository people;
    private final SheetRepository sheetRows;
    private final SheetService sheets;
    private final ClaimService claims;
    private final TransactionTemplate transaction;
    private final SettingService settings;
    private final Audit audit;

    public ClaimPlanService(ClaimPlanRepository plans, ClaimPlanDateRepository dates, ClaimPlanPauseRepository pauses,
            PersonRepository people,
            SheetRepository sheetRows, SheetService sheets, ClaimService claims, SettingService settings,
            PlatformTransactionManager transactions, Audit audit) {
        this.settings = settings;
        this.plans = plans;
        this.dates = dates;
        this.pauses = pauses;
        this.people = people;
        this.sheetRows = sheetRows;
        this.sheets = sheets;
        this.claims = claims;
        this.transaction = new TransactionTemplate(transactions);
        this.audit = audit;
    }

    /** The sheet's plans in the order they were added. */
    @Transactional(readOnly = true)
    public List<ClaimPlan> plans(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** How many claims each of the sheet's plans has made so far, by plan id (those with none left out). */
    @Transactional(readOnly = true)
    public Map<Long, Integer> made(String sheetId) {
        Map<Long, Integer> made = new HashMap<>();
        for (ClaimPlanDate d : dates.findBySheetId(sheetId)) made.merge(d.getPlanId(), 1, Integer::sum);
        return made;
    }

    /** The pauses of each of the sheet's plans, by plan id (those never paused left out). */
    @Transactional(readOnly = true)
    public Map<Long, List<ClaimPlanPause>> pauses(String sheetId) {
        Map<Long, List<ClaimPlanPause>> by = new HashMap<>();
        for (ClaimPlanPause p : pauses.findBySheetId(sheetId)) {
            by.computeIfAbsent(p.getPlanId(), k -> new ArrayList<>()).add(p);
        }
        return by;
    }

    /** Whether a plan date is skipped by one of the plan's pauses. */
    static Predicate<LocalDate> skippedBy(List<ClaimPlanPause> pauses) {
        return d -> pauses.stream().anyMatch(p -> p.skips(d));
    }

    /** Pause the plan from the sheet's today; refused when it is already paused. */
    @Transactional
    public ClaimPlan pause(String sheetId, String user, Long planId) {
        sheetRows.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        ClaimPlan plan = planOf(sheetId, planId);
        if (pauses.findByPlanIdAndUntilIsNull(planId).isPresent()) throw new PlanPauseRefusedException();
        pauses.saveAndFlush(new ClaimPlanPause(sheetId, planId, sheets.today(sheetId)));
        audit.recordOnCommit("PLAN_PAUSED sheet=" + sheetId + " by=" + user.strip() + " name=" + nameOf(plan)
                + " start=" + plan.getStart());
        return plan;
    }

    /** Resume the paused plan from the sheet's today; refused when it is not paused. */
    @Transactional
    public ClaimPlan resume(String sheetId, String user, Long planId) {
        sheetRows.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        ClaimPlan plan = planOf(sheetId, planId);
        ClaimPlanPause pause = pauses.findByPlanIdAndUntilIsNull(planId).orElseThrow(PlanPauseRefusedException::new);
        pause.resume(sheets.today(sheetId));
        pauses.saveAndFlush(pause);
        audit.recordOnCommit("PLAN_RESUMED sheet=" + sheetId + " by=" + user.strip() + " name=" + nameOf(plan)
                + " start=" + plan.getStart());
        return plan;
    }

    private ClaimPlan planOf(String sheetId, Long planId) {
        return plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(PlanNotFoundException::new);
    }

    private String nameOf(ClaimPlan plan) {
        return people.findByIdAndSheetId(plan.getPersonId(), plan.getSheetId()).map(Person::getName)
                .orElseThrow(PersonNotFoundException::new);
    }

    /** The sheet's today, from which a plan's next dates are counted. */
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    @Transactional
    public ClaimPlan add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate start,
            Integer every, Integer day, LocalDate end) {
        sheetRows.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if ((every == null) == (day == null) || end.isBefore(start)) throw new PlanRefusedException();
        if (every != null && every < 1) throw new PlanRefusedException();
        if (day != null && (day < 1 || day > 31)) throw new PlanRefusedException();
        ClaimPlan plan = plans.saveAndFlush(new ClaimPlan(sheetId, personId, amount, start, every, day, end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user.strip() + " name=" + person.getName()
                + " amount=" + amount.toPlainString() + " start=" + start
                + (day != null ? " day=" + day : " every=" + every) + " end=" + end);
        return plan;
    }

    /**
     * Make the plans' claims due by the sheet's today: how many were made. A viewer's generate, or one on an
     * archived sheet, goes through with nothing made, like a bulk change.
     */
    public int generate(String sheetId, String user) {
        if (settings.roleOf(sheetId, user) == Role.VIEWER || sheets.isArchived(sheetId)) return 0;
        LocalDate today = sheets.today(sheetId);
        int made = 0;
        Map<Long, List<ClaimPlanPause>> paused = pauses(sheetId);
        for (ClaimPlan plan : plans(sheetId)) {
            Predicate<LocalDate> skipped = skippedBy(paused.getOrDefault(plan.getId(), List.of()));
            for (LocalDate on : plan.datesUpTo(today)) {
                if (skipped.test(on)) continue;
                try {
                    Boolean done = transaction.execute(status -> make(sheetId, user, plan, on));
                    if (Boolean.TRUE.equals(done)) made++;
                } catch (PeriodClosedException | PersonRetiredException | PersonNotFoundException
                        | CorrectionRefusedException refused) {
                    // skipped: tried again next time
                }
            }
        }
        return made;
    }

    /** Make the plan's claim for the date unless it has made one for it: whether it made one. */
    private boolean make(String sheetId, String user, ClaimPlan plan, LocalDate on) {
        sheetRows.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (dates.existsByPlanIdAndMadeOn(plan.getId(), on)) return false;
        claims.add(sheetId, user, plan.getPersonId(), plan.getAmount(), on, "", null);
        dates.saveAndFlush(new ClaimPlanDate(sheetId, plan.getId(), on));
        return true;
    }
}
