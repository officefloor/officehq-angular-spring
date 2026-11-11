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
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The people's plans: an amount, a start date, every N days (N at least 1) or monthly on a day of
 * the month (1 to 31), and an end date not before the start, added from the person's row. Generating goes through every plan on the sheet
 * in the order they were added and, for each plan date (the start, then every N days; or that day in every month from the start's month, on the month's
 * last day when it is shorter, dates before the start left out) up to the
 * end and the sheet's today that the plan has made no claim for yet, adds a DRAFT claim with the
 * plan's amount, dated that day with an empty note, by the claim form's rules: a date the form
 * refuses is skipped, and tried again on the next generate. A plan can be paused and resumed; a plan
 * date on or after the day it was paused and before the day it was resumed is skipped for good.
 */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PersonRepository people;
    private final ClaimService claims;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final RoleService roles;
    private final Audit audit;

    public PlanService(PlanRepository plans, PersonRepository people, ClaimService claims, SheetService sheets,
            SheetRepository sheetRows, RoleService roles, Audit audit) {
        this.plans = plans;
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.roles = roles;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Plan> list(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /**
     * Add a plan to the person, every N days or monthly on a day; an end before the start, an interval
     * below 1, a day outside 1 to 31, or both or neither of every and day, is refused (400).
     */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if ((every == null) == (day == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give either every so many days or a day of the month.");
        }
        if (day != null && (day < 1 || day > 31)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The day of the month must be 1 to 31.");
        }
        if (every != null && every < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every must be a whole number of days, at least 1.");
        }
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's end cannot be before its start.");
        }
        Plan saved = plans.saveAndFlush(new Plan(sheetId, personId, amount, start, every, day, end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName() + " amount="
                + saved.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString() + " start="
                + saved.getStart() + (saved.getDay() != null ? " day=" + saved.getDay() : " every=" + saved.getEvery())
                + " end=" + saved.getEnd());
        return saved;
    }

    /** Pause the plan from the sheet's today; pausing a paused plan is refused (409). */
    @Transactional
    public Plan pause(String sheetId, String user, long planId) {
        return pauseOrResume(sheetId, user, planId, true);
    }

    /** Resume the plan on the sheet's today; resuming a plan that is not paused is refused (409). */
    @Transactional
    public Plan resume(String sheetId, String user, long planId) {
        return pauseOrResume(sheetId, user, planId, false);
    }

    private Plan pauseOrResume(String sheetId, String user, long planId, boolean pause) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan on this sheet."));
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        LocalDate today = sheets.today(sheetId);
        if (!(pause ? plan.pause(today) : plan.resume(today))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    pause ? "This plan is already paused." : "This plan is not paused.");
        }
        Plan saved = plans.saveAndFlush(plan);
        audit.recordOnCommit((pause ? "PLAN_PAUSED" : "PLAN_RESUMED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " start=" + saved.getStart());
        return saved;
    }

    /** Make the claims the sheet's plans are due to have made by its today: the claims made, in order. */
    @Transactional
    public List<Claim> generate(String sheetId, String user) {
        // the claim form refuses every claim a viewer would make, so for a viewer each plan date is
        // one the form refuses: skipped and tried again on the next generate; it makes nothing, answering 0
        sheets.refuseIfArchived(sheetId);
        sheets.load(sheetId, user);
        if (Role.VIEWER.equals(roles.roleOf(sheetId, user))) {
            return List.of();
        }
        // hold the sheet so two generates at once cannot both make the same plan date's claim
        sheetRows.findLockedById(sheetId);
        LocalDate today = sheets.today(sheetId);
        List<Claim> made = new ArrayList<>();
        for (Plan plan : plans.findBySheetIdOrderByIdAsc(sheetId)) {
            if (people.findByIdAndSheetId(plan.getPersonId(), sheetId).isEmpty()) {
                continue;
            }
            LocalDate last = plan.getEnd().isBefore(today) ? plan.getEnd() : today;
            for (LocalDate day : plan.datesUpTo(last)) {
                if (plan.hasMade(day) || plan.skips(day) || !claims.takesDate(sheetId, plan.getPersonId(), day)) {
                    continue;
                }
                made.add(claims.add(sheetId, user, plan.getPersonId(),
                        plan.getAmount().setScale(2, RoundingMode.UNNECESSARY), day, "", null, null, List.of()));
                plan.markMade(day);
            }
            plans.saveAndFlush(plan);
        }
        return made;
    }
}
