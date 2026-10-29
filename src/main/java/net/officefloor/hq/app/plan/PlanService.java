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
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's plans. Generating makes, for each plan in the order they were added, a draft claim of its amount on
 * each of its plan dates up to its end and the sheet's today that has none yet, dated that day with an empty note, by
 * every rule of the claim form; a date the form refuses is skipped and tried again next time.
 */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PersonRepository people;
    private final SettingsService settings;
    private final ClaimService claims;
    private final SheetService sheets;
    private final Audit audit;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository plans, PersonRepository people, ClaimService claims, SheetService sheets,
            SettingsService settings, Audit audit, PlatformTransactionManager transactions) {
        this.settings = settings;
        this.plans = plans;
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    /** The sheet's plans in the order they were added. */
    @Transactional(readOnly = true)
    public List<Plan> list(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /**
     * Adds a plan for the person, every so many days or monthly on a day of the month. Refused when the amount is
     * negative or has more than two decimals, both or neither of the interval and the day are given, the interval is
     * less than one day, the day is not 1 to 31, or the end is before the start.
     */
    @Transactional
    public Plan add(String by, String sheetId, Long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end) {
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2
                || amount.precision() - amount.scale() > 12) {
            throw refused("Enter an amount of at least 0 with up to two decimals.");
        }
        if ((every == null) == (day == null)) {
            throw refused("Enter either a number of days or a day of the month.");
        }
        if (every != null && every < 1) {
            throw refused("Enter a whole number of days of at least 1.");
        }
        if (day != null && (day < 1 || day > 31)) {
            throw refused("Enter a day of the month from 1 to 31.");
        }
        if (start == null || end == null) {
            throw refused("Enter a start date and an end date.");
        }
        if (end.isBefore(start)) {
            throw refused("The end date is before the start date.");
        }
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        BigDecimal scaled = amount.setScale(2, RoundingMode.UNNECESSARY);
        Plan saved = plans.saveAndFlush(new Plan(sheetId, personId, scaled, start, every, day, end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + by + " name=" + person.getName() + " amount="
                + scaled.toPlainString() + " start=" + start + (day != null ? " day=" + day : " every=" + every) + " end=" + end);
        return saved;
    }

    /**
     * Pauses (true) or resumes (false) the plan from the sheet's today. Refused when pausing a paused plan or resuming
     * a running one.
     */
    @Transactional
    public Plan setPaused(String by, String sheetId, Long planId, boolean paused) {
        sheets.lock(sheetId);
        Plan plan = plans.findById(planId).filter(p -> p.getSheetId().equals(sheetId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such plan."));
        if (plan.isPaused() == paused) {
            throw refused(paused ? "The plan is already paused." : "The plan is not paused.");
        }
        LocalDate today = sheets.load(sheetId).getToday();
        if (paused) {
            plan.pause(today);
        } else {
            plan.resume(today);
        }
        Plan saved = plans.saveAndFlush(plan);
        Person person = people.findByIdAndSheetId(plan.getPersonId(), sheetId).orElseThrow();
        audit.recordOnCommit((paused ? "PLAN_PAUSED" : "PLAN_RESUMED") + " sheet=" + sheetId + " by=" + by + " name="
                + person.getName() + " start=" + plan.getStart());
        return saved;
    }

    /** What a generate made: the claims, in the order they were made, and every plan of the sheet as it stands. */
    public record Generated(List<Claim> claims, List<Plan> plans) {
    }

    /**
     * Makes the plans' claims. Each claim is made in a change of its own, so one the claim form refuses changes
     * nothing and the others still go through. A user who may change nothing on the sheet makes none.
     */
    public Generated generate(String by, String sheetId) {
        if (settings.isReadOnly(sheetId, by)) {
            return new Generated(List.of(), list(sheetId));
        }
        LocalDate today = sheets.load(sheetId).getToday();
        List<Claim> made = new ArrayList<>();
        for (Plan plan : tx.execute(s -> plans.findBySheetIdOrderByIdAsc(sheetId))) {
            LocalDate last = plan.getEnd().isBefore(today) ? plan.getEnd() : today;
            for (LocalDate on : plan.datesUpTo(last)) {
                if (plan.hasMade(on) || plan.isSkipped(on)) {
                    continue;
                }
                LocalDate date = on;
                try {
                    made.add(tx.execute(s -> {
                        Plan current = plans.findById(plan.getId()).orElseThrow();
                        if (current.hasMade(date) || current.isSkipped(date)) {
                            return null;
                        }
                        Claim claim = claims.add(by, sheetId, current.getPersonId(), current.getAmount(), date, "",
                                null, null, null);
                        current.markMade(date);
                        plans.saveAndFlush(current);
                        return claim;
                    }));
                } catch (ResponseStatusException refused) {
                    // the claim form refused this date: it is tried again next time
                }
            }
        }
        made.removeIf(c -> c == null);
        return new Generated(made, list(sheetId));
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, reason);
    }
}
