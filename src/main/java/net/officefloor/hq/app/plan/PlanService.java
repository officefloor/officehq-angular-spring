package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's plans, and makes their claims. */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PersonRepository people;
    private final ClaimService claims;
    private final SheetService sheets;
    private final RoleService roles;
    private final Audit audit;

    public PlanService(PlanRepository plans, PersonRepository people, ClaimService claims, SheetService sheets,
            RoleService roles, Audit audit) {
        this.roles = roles;
        this.plans = plans;
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's plans, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Plan> list(String sheetId) {
        sheets.today(sheetId);
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The sheet's today, from which the plans' next dates are told. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    /** Add a plan to a person, run every so many days or monthly on a day of the month (exactly one of them);
     *  an amount a claim would refuse (below 0.00), an interval below 1 day, a day of the month outside 1 to 31,
     *  or an end before the start is refused. */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start,
            Integer every, Integer day, LocalDate end) {
        sheets.today(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (amount.signum() < 0) {
            throw new PlanRefusedException("Enter an amount of 0.00 or more.");
        }
        if (day != null) {
            if (every != null) {
                throw new PlanRefusedException("Enter either every how many days or a day of the month, not both.");
            }
            if (day < 1 || day > 31) {
                throw new PlanRefusedException("Enter a day of the month from 1 to 31.");
            }
        } else if (every == null || every < 1) {
            throw new PlanRefusedException("Enter every how many days, 1 or more.");
        }
        if (end.isBefore(start)) {
            throw new PlanRefusedException("The end cannot be before the start.");
        }
        BigDecimal a = amount.setScale(2);
        Plan plan = plans.saveAndFlush(new Plan(sheetId, personId, a, start, every, day, end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + a.toPlainString() + " start=" + start
                + (day != null ? " day=" + day : " every=" + every) + " end=" + end);
        return plan;
    }

    /** Pause a running plan, or resume a paused one, from the sheet's today: plan dates from the day paused
     *  until the day resumed are skipped. Pausing a paused plan or resuming a running one is refused. */
    @Transactional
    public Plan setPaused(String sheetId, String user, long planId, boolean paused) {
        LocalDate today = sheets.today(sheetId);
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new PlanRefusedException("No such plan."));
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        if (paused == plan.isPaused()) {
            throw new PlanRefusedException(paused ? "The plan is already paused." : "The plan is not paused.");
        }
        if (paused) {
            plan.pause(today);
        } else {
            plan.resume(today);
        }
        plan = plans.saveAndFlush(plan);
        audit.recordOnCommit((paused ? "PLAN_PAUSED" : "PLAN_RESUMED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " start=" + plan.getStart());
        return plan;
    }

    /** Change a plan's amount from the sheet's today: the claims it makes from then on carry the new amount,
     *  those already made keep theirs. An amount a claim would refuse (below 0.00) is refused. */
    @Transactional
    public Plan changeAmount(String sheetId, String user, long planId, BigDecimal amount) {
        LocalDate today = sheets.today(sheetId);
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new PlanRefusedException("No such plan."));
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        if (amount.signum() < 0) {
            throw new PlanRefusedException("Enter an amount of 0.00 or more.");
        }
        BigDecimal a = amount.setScale(2);
        plan.changeAmount(today, a);
        plan = plans.saveAndFlush(plan);
        audit.recordOnCommit("PLAN_CHANGED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " start=" + plan.getStart() + " amount=" + a.toPlainString());
        return plan;
    }

    /** What a generate did: the claims it made, in the order made, the sheet's plans, and its today. */
    public record Generated(List<Claim> claims, List<Plan> plans, LocalDate today) {
    }

    /** For every plan on the sheet in the order they were added, make a DRAFT claim of its amount with an
     *  empty note on each plan date up to its end and the sheet's today it has made none for yet; a date the
     *  claim form refuses is skipped, and tried again next time. Like a bulk action, a viewer's generate goes
     *  through with nothing made. */
    @Transactional
    public Generated generate(String sheetId, String user) {
        LocalDate today = sheets.today(sheetId);
        List<Plan> all = plans.findBySheetIdOrderByIdAsc(sheetId);
        if (roles.isViewer(sheetId, user)) {
            return new Generated(List.of(), all, today);
        }
        List<Claim> made = new ArrayList<>();
        for (Plan plan : all) {
            if (people.findByIdAndSheetId(plan.getPersonId(), sheetId).isEmpty()) {
                continue; // the person is no longer on the sheet
            }
            for (LocalDate date : plan.datesDue(today)) {
                claims.addIfAccepted(sheetId, user, plan.getPersonId(), plan.getAmount(), date, "")
                        .ifPresent(claim -> {
                            made.add(claim);
                            plan.generated(date);
                        });
            }
            plans.saveAndFlush(plan);
        }
        return new Generated(made, all, today);
    }
}
