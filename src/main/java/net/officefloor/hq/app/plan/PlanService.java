package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
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
    private final SheetRepository sheetStore;
    private final ClaimService claims;
    private final ClaimRepository claimStore;
    private final RoleService roles;
    private final TransactionTemplate eachClaim;
    private final Audit audit;

    public PlanService(PlanRepository plans, PersonRepository people, SheetService sheets,
            SheetRepository sheetStore, ClaimService claims, ClaimRepository claimStore, RoleService roles, PlatformTransactionManager transactions, Audit audit) {
        this.plans = plans;
        this.people = people;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.claims = claims;
        this.claimStore = claimStore;
        this.roles = roles;
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
     * end is before the start. A plan may name one of the sheet's descendants (blank or null: none): its claims
     * are then made on that sheet; one that is not a descendant is refused. PLAN_ADDED then ends with
     * sheet=&lt;that sheet&gt;.
     */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end, String target) {
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
        String targetSheet = target == null || target.isBlank() ? null : target.trim();
        sheets.lock(sheetId);
        if (targetSheet != null && !sheetStore.descendants(sheetId).contains(targetSheet)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A plan can only name a sheet below this one: " + targetSheet + " is not.");
        }
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        BigDecimal money = amount.setScale(2, RoundingMode.UNNECESSARY);
        Plan saved = plans.saveAndFlush(new Plan(sheetId, personId, money, start, every, day, end, targetSheet));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + money.toPlainString() + " start=" + start + (day != null ? " day=" + day : " every=" + every) + " end=" + end
                + (targetSheet != null ? " sheet=" + targetSheet : ""));
        return saved;
    }

    /**
     * Pause a running plan, or resume a paused one, on the sheet's today: a plan date on or after the day
     * paused and before the day resumed is skipped. Pausing a paused plan or resuming a running one is refused.
     */
    @Transactional
    public Plan pause(String sheetId, String user, long planId, boolean on) {
        Sheet sheet = sheets.lock(sheetId);
        Plan plan = plans.findByIdAndSheetId(planId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan on this sheet."));
        if (plan.isPaused() == on) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    on ? "The plan is paused already." : "The plan is not paused.");
        }
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (on) {
            plan.pause(sheet.getToday());
        } else {
            plan.resume(sheet.getToday());
        }
        Plan saved = plans.saveAndFlush(plan);
        audit.recordOnCommit((on ? "PLAN_PAUSED" : "PLAN_RESUMED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " start=" + plan.getStart());
        return saved;
    }

    /**
     * Change a plan's amount (not below 0.00) on the sheet's today: the claims generated from then on take it,
     * and so do those it made that are still DRAFT, each edited as the claim form would edit it (its date,
     * note and the number it corrects kept) and audited as CLAIM_EDITED, after the plan's change, in the order
     * of their plan dates. Each is edited on its own: one the form refuses keeps its amount. Claims in any
     * other status keep theirs.
     */
    public Plan changeAmount(String sheetId, String user, long planId, BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's amount cannot be below 0.00.");
        }
        BigDecimal money = amount.setScale(2, RoundingMode.UNNECESSARY);
        Plan saved = eachClaim.execute(status -> {
            Sheet sheet = sheets.lock(sheetId);
            Plan plan = plans.findByIdAndSheetId(planId, sheetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan on this sheet."));
            Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
            plan.changeAmount(sheet.getToday(), money);
            Plan changed = plans.saveAndFlush(plan);
            audit.recordOnCommit("PLAN_CHANGED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                    + " start=" + plan.getStart() + " amount=" + money.toPlainString());
            return changed;
        });
        String claimSheet = saved.getTargetSheetId() != null ? saved.getTargetSheetId() : sheetId;
        for (Long claimId : saved.getMadeClaimIds()) {
            try {
                eachClaim.executeWithoutResult(status -> {
                    Claim claim = claimStore.findByIdAndSheetId(claimId, claimSheet).orElse(null);
                    if (claim != null && claim.getStatus() == ClaimStatus.DRAFT) {
                        claims.edit(claimSheet, user, claimId, money, claim.getDate(), claim.getNote(),
                                claim.getCorrects(), null, null);
                    }
                });
            } catch (ResponseStatusException refused) {
                // the claim form refuses this edit: the claim keeps its amount
            }
        }
        return plans.findByIdAndSheetId(planId, sheetId).orElse(saved);
    }

    /**
     * For every plan on the sheet, in the order they were added, make a DRAFT claim with the plan's amount
     * and an empty note for each plan date up to its end and the sheet's today that has none yet, dated
     * that day. Each claim is added by the claim form's rules, on its own: a date the form refuses is
     * skipped, and tried again on the next generate. A viewer's generate makes nothing: every claim it
     * would make is refused. A plan naming a descendant sheet makes its claims there, by that sheet's rules,
     * for the person there of the plan's person's name: none while there is no such person.
     */
    public Generated generate(String sheetId, String user) {
        LocalDate today = sheets.today(sheetId);
        List<Claim> made = new ArrayList<>();
        if (Role.VIEWER.equals(roles.roleOf(sheetId, user))) {
            return new Generated(made, list(sheetId));
        }
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
        Long personId = plan.getPersonId();
        String claimSheet = sheetId;
        if (plan.getTargetSheetId() != null) {
            claimSheet = plan.getTargetSheetId();
            Person own = people.findByIdAndSheetId(plan.getPersonId(), sheetId).orElse(null);
            Person there = own == null ? null : people.findBySheetIdAndName(claimSheet, own.getName()).orElse(null);
            if (there == null) {
                return null;
            }
            personId = there.getId();
        }
        Claim claim = claims.add(claimSheet, user, personId, plan.getAmount(), date, "", null);
        plan.made(date, claim.getId());
        plans.saveAndFlush(plan);
        return claim;
    }
}
