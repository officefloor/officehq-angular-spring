package net.officefloor.hq.app.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the plans of a sheet's people. A plan has an amount (more than 0.00), a start date, an interval of every so
 * many days (at least 1) or, run monthly, a day of the month (1 to 31), and an end date not before its start. Generate makes, for every plan on the sheet in the order
 * they were added, a DRAFT claim of its amount with an empty note for each plan date (the start, then every so many
 * days) up to its end and the sheet's today that it has made none for yet, each added as the claim form adds one: a
 * date the claim form refuses is skipped, and tried again by the next generate.
 */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final PlanDateRepository dates;
    private final PersonRepository people;
    private final ClaimService claims;
    private final SheetService sheets;
    private final Audit audit;
    /** Each plan date's claim is made in its own transaction, so one refused rolls back only itself. */
    private final TransactionTemplate each;

    public PlanService(PlanRepository plans, PlanDateRepository dates, PersonRepository people, ClaimService claims,
            SheetService sheets, Audit audit, PlatformTransactionManager transactions) {
        this.plans = plans;
        this.dates = dates;
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.audit = audit;
        this.each = new TransactionTemplate(transactions);
        this.each.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** The sheet's plans in the order they were added. */
    public List<Plan> list(String sheetId) {
        return plans.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** How many claims each of the sheet's plans has made, by plan id. */
    public Map<Long, Long> generatedCounts(String sheetId) {
        return dates.findBySheetId(sheetId).stream()
                .collect(Collectors.groupingBy(PlanDate::getPlanId, Collectors.counting()));
    }

    /** The plan's first five dates after the sheet's today, up to its end. */
    public List<LocalDate> nextDates(String sheetId, Plan plan) {
        return plan.datesAfter(sheets.today(sheetId), 5);
    }

    /** The first five dates after the sheet's today of each of the sheet's plans, by plan id. */
    public Map<Long, List<LocalDate>> nextDates(String sheetId) {
        List<Plan> all = list(sheetId);
        if (all.isEmpty()) {
            return Map.of();
        }
        LocalDate today = sheets.today(sheetId);
        return all.stream().collect(Collectors.toMap(Plan::getId, plan -> plan.datesAfter(today, 5)));
    }

    @Transactional
    public Plan add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate start, Integer every,
            Integer day, LocalDate end) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        if (amount.signum() <= 0) {
            throw new PlanRefusedException("A plan's amount must be more than 0.00");
        }
        if ((every == null) == (day == null)) {
            throw new PlanRefusedException("A plan repeats either every so many days or on a day of the month");
        }
        if (every != null && every < 1) {
            throw new PlanRefusedException("A plan repeats every 1 day or more");
        }
        if (day != null && (day < 1 || day > 31)) {
            throw new PlanRefusedException("A plan's day of the month is 1 to 31");
        }
        if (end.isBefore(start)) {
            throw new PlanRefusedException("A plan's end may not be before its start");
        }
        Plan saved = plans.saveAndFlush(new Plan(sheetId, personId, amount, start, every, day, end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " amount=" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + " start=" + start + (day != null ? " day=" + day : " every=" + every) + " end=" + end);
        return saved;
    }

    /** Make the claims of every plan date due and not yet made: how many were made. */
    public int generate(String sheetId, String user) {
        LocalDate today = sheets.today(sheetId);
        Map<Long, Set<LocalDate>> made = dates.findBySheetId(sheetId).stream()
                .collect(Collectors.groupingBy(PlanDate::getPlanId,
                        Collectors.mapping(PlanDate::getDate, Collectors.toSet())));
        int count = 0;
        for (Plan plan : list(sheetId)) {
            Set<LocalDate> done = made.getOrDefault(plan.getId(), Set.of());
            for (LocalDate date : plan.datesUpTo(today)) {
                if (!done.contains(date) && make(sheetId, user, plan, date)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Make the plan's claim on the date as the claim form would: false when it refuses it (nothing is kept). */
    private boolean make(String sheetId, String user, Plan plan, LocalDate date) {
        try {
            each.executeWithoutResult(status -> {
                claims.add(sheetId, user, plan.getPersonId(), plan.getAmount(), date, "", null);
                dates.saveAndFlush(new PlanDate(sheetId, plan.getId(), date));
            });
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
