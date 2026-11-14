package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The plans on a sheet, and the generate that makes their claims. */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PlanClaimRepository made;
    private final PersonRepository people;
    private final ClaimService claims;
    private final SheetService sheets;
    private final Audit audit;

    public PlanService(PlanRepository plans, PlanClaimRepository made, PersonRepository people, ClaimService claims,
            SheetService sheets, Audit audit) {
        this.plans = plans;
        this.made = made;
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
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

    /** Add a plan for a person: an amount of 0.00 or more with up to two decimals, a start (blank: the
     *  sheet's today), every so many days (a whole number, at least 1) and an end on or after the start. */
    @Transactional
    public Plan add(String sheetId, String user, Long personId, String amount, LocalDate start, String every,
            LocalDate end) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + personId + " on sheet " + sheetId));
        BigDecimal figure = amountOf(amount);
        int days = everyOf(every);
        LocalDate today = sheets.today(sheetId);
        LocalDate from = start != null ? start : today;
        if (end == null || end.isBefore(from)) {
            throw new PlanRefusedException("A plan must end on or after its start");
        }
        Plan saved = plans.saveAndFlush(new Plan(sheetId, personId, figure, from, days, end, today));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + saved.getAmount().toPlainString() + " start=" + saved.getStart()
                + " every=" + saved.getEvery() + " end=" + saved.getEnd());
        return saved;
    }

    /** For every plan on the sheet, in the order they were added, add a DRAFT claim of its amount with
     *  an empty note for each plan date up to its end and the sheet's today it has made none for yet,
     *  as the claim form would; a date the form refuses is passed over, to be tried again next time.
     *  The claims made, in the order they were. */
    @Transactional
    public List<Claim> generate(String sheetId, String user) {
        sheets.lock(sheetId);
        LocalDate today = sheets.today(sheetId);
        List<Plan> them = plans.findBySheetIdOrderByIdAsc(sheetId);
        Set<String> done = new HashSet<>();
        for (PlanClaim c : made.findByPlanIdInOrderByIdAsc(them.stream().map(Plan::getId).toList())) {
            done.add(c.getPlanId() + "@" + c.getDate());
        }
        List<Claim> added = new ArrayList<>();
        for (Plan plan : them) {
            for (LocalDate date : plan.datesUpTo(today)) {
                if (done.contains(plan.getId() + "@" + date)
                        || !claims.accepts(sheetId, plan.getPersonId(), date)) {
                    continue;
                }
                Claim claim = claims.add(sheetId, user, plan.getPersonId(), plan.getAmount(), date, "", null);
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
}
