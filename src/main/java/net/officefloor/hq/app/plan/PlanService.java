package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** The plans people on a sheet have, and the draft claims they make. */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PersonRepository people;
    private final ClaimService claims;
    private final SheetService sheets;
    private final TransactionTemplate transaction;
    private final Audit audit;
    private final RoleService roles;

    public PlanService(PlanRepository plans, PersonRepository people, ClaimService claims, SheetService sheets,
            PlatformTransactionManager transactions, Audit audit, RoleService roles) {
        this.plans = plans;
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.transaction = new TransactionTemplate(transactions);
        this.audit = audit;
        this.roles = roles;
    }

    /** The plans on the sheet in the order they were added. */
    @Transactional(readOnly = true)
    public List<Plan> list(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a plan for a person, repeating either every so many days or monthly on a day of the month;
     *  a start left out takes the sheet's today. Refused for an amount that is not above zero, neither
     *  or both of an interval and a day, an interval below one day, a day outside 1 to 31, and an end
     *  before the start. */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's amount must be above zero");
        }
        if ((every == null) == (day == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A plan repeats either every so many days or on a day of the month");
        }
        if (day != null && (day < 1 || day > 31)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's day of the month is 1 to 31");
        }
        if (every != null && every < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan repeats every one day or more");
        }
        LocalDate from = start != null ? start : sheets.today(sheetId);
        if (end.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan cannot end before it starts");
        }
        Plan saved = plans.saveAndFlush(new Plan(sheetId, person.getId(), amount, from, every, day, end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + amount.setScale(2).toPlainString() + " start=" + from
                + (day != null ? " day=" + day : " every=" + every) + " end=" + end);
        return saved;
    }

    /** Pause the plan from the sheet's today, or resume it on that day; refused for pausing a paused
     *  plan or resuming one that is not paused. */
    @Transactional
    public Plan setPaused(String sheetId, String user, long planId, boolean paused) {
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan on this sheet"));
        if (plan.isPaused() == paused) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    paused ? "The plan is already paused" : "The plan is not paused");
        }
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId).orElseThrow();
        LocalDate today = sheets.today(sheetId);
        if (paused) {
            plan.pause(today);
        } else {
            plan.resume(today);
        }
        Plan saved = plans.saveAndFlush(plan);
        audit.recordOnCommit((paused ? "PLAN_PAUSED" : "PLAN_RESUMED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " start=" + plan.getStartDate());
        return saved;
    }

    /** Make, for every plan on the sheet in the order they were added, a draft claim of its amount
     *  with an empty note for each of its dates up to its end and the sheet's today that has none
     *  yet, oldest first. Each claim is added as the claim form adds it, in a transaction of its
     *  own: a date the form refuses is skipped, and tried again by the next generate. Returns how
     *  many claims were made. A viewer's generate makes none. */
    public int generate(String sheetId, String user) {
        if (SheetRole.VIEWER.equals(roles.roleOf(sheetId, user))) {
            return 0;
        }
        LocalDate today = sheets.today(sheetId);
        int made = 0;
        for (Plan plan : plans.findBySheetIdOrderByIdAsc(sheetId)) {
            for (LocalDate date : plan.dueUpTo(today)) {
                try {
                    transaction.executeWithoutResult(status -> {
                        Plan locked = plans.findById(plan.getId()).orElseThrow();
                        if (!locked.dueUpTo(date).contains(date)) {
                            return;
                        }
                        claims.add(sheetId, user, locked.getPersonId(), locked.getAmount(), date, "");
                        locked.markMade(date);
                        plans.saveAndFlush(locked);
                    });
                    made++;
                } catch (ResponseStatusException refused) {
                    // the claim form refused the date: it is tried again next time
                }
            }
        }
        return made;
    }
}
