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
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The people's plans: an amount, a start date, every N days (N at least 1) and an end date not
 * before the start, added from the person's row. Generating goes through every plan on the sheet
 * in the order they were added and, for each plan date (the start, then every N days) up to the
 * end and the sheet's today that the plan has made no claim for yet, adds a DRAFT claim with the
 * plan's amount, dated that day with an empty note, by the claim form's rules: a date the form
 * refuses is skipped, and tried again on the next generate.
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

    /** Add a plan to the person; an end before the start, or an interval below 1, is refused (400). */
    @Transactional
    public Plan add(String sheetId, String user, long personId, BigDecimal amount, LocalDate start, int every,
            LocalDate end) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (every < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every must be a whole number of days, at least 1.");
        }
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A plan's end cannot be before its start.");
        }
        Plan saved = plans.saveAndFlush(new Plan(sheetId, personId, amount, start, every, end));
        audit.recordOnCommit("PLAN_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName() + " amount="
                + saved.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString() + " start="
                + saved.getStart() + " every=" + saved.getEvery() + " end=" + saved.getEnd());
        return saved;
    }

    /** Make the claims the sheet's plans are due to have made by its today: the claims made, in order. */
    @Transactional
    public List<Claim> generate(String sheetId, String user) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        // hold the sheet so two generates at once cannot both make the same plan date's claim
        sheetRows.findLockedById(sheetId);
        LocalDate today = sheets.today(sheetId);
        List<Claim> made = new ArrayList<>();
        for (Plan plan : plans.findBySheetIdOrderByIdAsc(sheetId)) {
            if (people.findByIdAndSheetId(plan.getPersonId(), sheetId).isEmpty()) {
                continue;
            }
            LocalDate last = plan.getEnd().isBefore(today) ? plan.getEnd() : today;
            for (LocalDate day = plan.getStart(); !day.isAfter(last); day = day.plusDays(plan.getEvery())) {
                if (plan.hasMade(day) || !claims.takesDate(sheetId, plan.getPersonId(), day)) {
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
