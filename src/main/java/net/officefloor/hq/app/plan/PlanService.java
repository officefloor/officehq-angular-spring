package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.setting.SheetParentLink;
import net.officefloor.hq.app.setting.SheetParentRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlanService {

    private final PlanRepository plans;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ClaimService claims;
    private final Audit audit;
    private final SettingService settings;
    private final SheetParentRepository parents;
    private final TransactionTemplate transaction;

    public PlanService(PlanRepository plans, PersonRepository people, SheetService sheets, SheetRepository sheetRows,
            ClaimService claims, Audit audit, SettingService settings, SheetParentRepository parents,
            PlatformTransactionManager transactions) {
        this.parents = parents;
        this.plans = plans;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.claims = claims;
        this.audit = audit;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** The sheet's plans in the order they were added. */
    @Transactional(readOnly = true)
    public List<Plan> list(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The sheet's today. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    /** Add a plan to a person: refused for an amount not above 0.00, neither or both of an interval and a
     *  day of the month, an interval below 1 day, a day of the month outside 1 to 31, or an end before the
     *  start, or a sheet that is not one of the sheet's descendants. No start means the sheet's today; no
     *  sheet means the plan's claims are made on its own sheet. */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end, String sheet) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (amount.signum() <= 0) throw refused("The amount must be more than 0.00.");
        if ((every == null) == (day == null)) throw refused("Give either every (days) or a day of the month.");
        if (every != null && every < 1) throw refused("Every must be a whole number of days, at least 1.");
        if (day != null && (day < 1 || day > 31)) throw refused("The day of the month must be 1 to 31.");
        LocalDate from = start != null ? start : sheets.today(sheetId);
        if (end.isBefore(from)) throw refused("The end date cannot be before the start date.");
        String target = sheet == null || sheet.isBlank() ? null : sheet.trim();
        if (target != null && !descendantsOf(sheetId).contains(target))
            throw refused("The sheet is not one of this sheet's descendants.");
        Plan plan = plans.saveAndFlush(new Plan(sheetId, personId, amount.setScale(2), from, every, day,
                end, target));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + plan.getAmount().toPlainString() + " start=" + from + (day != null ? " day=" + day : " every=" + every) + " end=" + end
                + (target != null ? " sheet=" + target : ""));
        return plan;
    }

    /** Pause (true) or resume (false) the plan on the sheet's today: refused for pausing a paused plan or
     *  resuming a running one. */
    @Transactional
    public Plan pause(String sheetId, String user, long planId, boolean on) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        Plan plan = plans.findForUpdate(planId).filter((p) -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan."));
        if (on && plan.isPaused()) throw refused("The plan is paused already.");
        if (!on && !plan.isPaused()) throw refused("The plan is not paused.");
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        LocalDate today = sheets.today(sheetId);
        if (on) plan.pause(today);
        else plan.resume(today);
        plans.saveAndFlush(plan);
        audit.recordOnCommit((on ? "PLAN_PAUSED" : "PLAN_RESUMED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " start=" + plan.getStart());
        return plan;
    }

    /** Change the plan's amount on the sheet's today, for the claims generated from then on: refused for an
     *  amount not above 0.00 or with more than two decimals. */
    @Transactional
    public Plan changeAmount(String sheetId, String user, long planId, BigDecimal amount) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        Plan plan = plans.findForUpdate(planId).filter((p) -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan."));
        if (amount.signum() <= 0) throw refused("The amount must be more than 0.00.");
        if (amount.stripTrailingZeros().scale() > 2) throw refused("The amount has at most two decimals.");
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        plan.changeAmount(sheets.today(sheetId), amount.setScale(2));
        plans.saveAndFlush(plan);
        audit.recordOnCommit("PLAN_CHANGED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " start=" + plan.getStart() + " amount=" + plan.getAmount().toPlainString());
        return plan;
    }

    /** For every plan on the sheet, in the order they were added, make a DRAFT claim of its amount, dated
     *  the plan date with an empty note, for each plan date up to its end and the sheet's today that has
     *  none yet and no pause skips. Each claim is added by the claim form's rules, on its own: a date the form refuses is
     *  skipped and tried again next time. A plan naming a descendant sheet makes its claims on that sheet, by
     *  its rules and in its audit, for the person of the same name there; none while there is no such person.
     *  The claims made, oldest first; a viewer makes none. */
    public List<Claim> generate(String sheetId, String user) {
        sheets.load(sheetId);
        if (settings.roleOf(sheetId, user) == Role.VIEWER) return List.of();
        LocalDate today = sheets.today(sheetId);
        List<Claim> made = new ArrayList<>();
        for (Plan plan : plans.findBySheetIdOrderByIdAsc(sheetId)) {
            for (LocalDate date : plan.datesUpTo(today)) {
                if (plan.hasGenerated(date)) continue;
                try {
                    Claim claim = transaction.execute((status) -> generateOn(sheetId, user, plan.getId(), date));
                    if (claim != null) made.add(claim);
                } catch (ResponseStatusException refused) {
                    // The claim form refuses the date: it is tried again by the next generate.
                }
            }
        }
        return made;
    }

    /** Make the plan's claim for the date, unless it has one already. */
    private Claim generateOn(String sheetId, String user, long planId, LocalDate date) {
        Plan plan = plans.findForUpdate(planId).orElse(null);
        if (plan == null || plan.hasGenerated(date)) return null;
        String target = plan.getTargetSheetId();
        Long personId = plan.getPersonId();
        if (target != null) {
            Person person = people.findByIdAndSheetId(personId, sheetId).orElse(null);
            if (person == null) return null;
            Person there = people.findBySheetIdAndNameKey(target, Person.keyOf(person.getName())).orElse(null);
            if (there == null) return null;
            personId = there.getId();
        }
        Claim claim = claims.add(target != null ? target : sheetId, user, personId, plan.getAmount(), date, "",
                null, null, null);
        plan.markGenerated(date);
        plans.saveAndFlush(plan);
        return claim;
    }

    /** The sheet's descendants: the sheets naming it as their parent, then theirs, and so on. */
    private Set<String> descendantsOf(String sheetId) {
        Set<String> found = new HashSet<>();
        Deque<String> next = new ArrayDeque<>(List.of(sheetId));
        while (!next.isEmpty()) {
            for (SheetParentLink child : parents.findByParentId(next.pop())) {
                if (!child.getSheetId().equals(sheetId) && found.add(child.getSheetId())) next.add(child.getSheetId());
            }
        }
        return found;
    }

    private static ResponseStatusException refused(String why) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, why);
    }
}
