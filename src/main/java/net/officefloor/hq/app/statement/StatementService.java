package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonGradeService;
import net.officefloor.hq.app.person.PersonHold;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The statements taken for people on a sheet. Taking one records the person's due-total and binds
 *  each claim of theirs that counts to the figure it shows then; the rest are left unbound. */
@Service
public class StatementService {

    /** Written for a due-total that has no rate to work from. */
    private static final String NO_RATE = "n/a";

    private final StatementRepository statements;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PersonRepository people;
    private final RateService rates;
    private final PersonGradeService grades;
    private final PersonService personService;
    private final SheetService sheets;
    private final SettingService settings;
    private final History history;
    private final Audit audit;

    public StatementService(StatementRepository statements, ClaimRepository claims, ClaimService claimService,
            PersonRepository people, RateService rates, PersonGradeService grades, PersonService personService,
            SheetService sheets, SettingService settings, History history, Audit audit) {
        this.history = history;
        this.statements = statements;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
        this.rates = rates;
        this.grades = grades;
        this.personService = personService;
        this.sheets = sheets;
        this.settings = settings;
        this.audit = audit;
    }

    /** Each person's statement summary on the sheet, by person id; a person with none is absent. */
    public Map<Long, StatementSummary> summaries(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(Statement::isLive)
                .collect(Collectors.groupingBy(Statement::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), StatementSummary::of)));
    }

    /** Each person's statement summary on the sheet as their statements stood at the end of a day, by
     *  person id (null: as they stand now); a person with none is absent. */
    public Map<Long, StatementSummary> summaries(String sheetId, LocalDate asOf) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(statement -> asOf == null ? statement.isLive() : statement.stoodAsOf(asOf))
                .collect(Collectors.groupingBy(Statement::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), StatementSummary::of)));
    }

    public StatementSummary summary(String sheetId, Long personId) {
        return StatementSummary.of(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId).stream()
                .filter(Statement::isLive).toList());
    }

    /** Take a statement for a person now, or as of a day on or before the sheet's today (null: now):
     *  their claims, newest first, as bound by it. Taken as of a day, it binds their claims that
     *  counted at the end of it to their figures as they stood then (every change made while the
     *  sheet's today was on or before it counts, every later one does not), that day standing in for
     *  the sheet's today in every figure; it is dated that day. A day after the sheet's today, or
     *  before the person was on the sheet, is refused. */
    @Transactional
    public List<Claim> take(String sheetId, String user, Long personId, LocalDate asOf) {
        // Lock the sheet, so a claim changed or a statement taken at the same moment waits.
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + personId + " on sheet " + sheetId));
        LocalDate today = sheets.today(sheetId);
        if (asOf != null && asOf.isAfter(today)) {
            throw new StatementRefusedException("A statement is taken as of a day on or before the sheet's today " + today);
        }
        if (asOf != null && !person.onSheetAsOf(asOf)) {
            throw new StatementRefusedException(person.getName() + " was not on the sheet on " + asOf);
        }
        LocalDate day = asOf != null ? asOf : today;
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, personId);
        List<PersonHold> holds = asOf != null ? personService.holdsAsOf(personId, asOf) : personService.holds(personId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean anyFigure = false;
        int counted = 0;
        for (Claim claim : theirs) {
            // The claim as it stood at the end of the day; one not yet theirs then is left out.
            Claim then = asOf == null ? claim
                    : history.claim(claim.getId(), asOf).filter(was -> was.getPersonId().equals(personId)).orElse(null);
            // A held or deferred claim, or one left out for the person's retirement, is left out of the
            // statement, whether or not an earlier one bound it.
            if (then == null || !then.counts() || then.heldBy(holds) || then.deferredOn(day)
                    || then.retiredOn(person.getRetiredFrom(), day)) {
                claim.unbind();
                continue;
            }
            // The statement binds the figure; the late charge on top keeps following the sheet's today.
            BigDecimal figure = claimService.figureOf(sheetId, person, then, asOf);
            if (figure != null) {
                total = total.add(claimService.dueOf(sheetId, person, then, asOf));
                anyFigure = true;
            }
            claim.bind(figure);
            counted++;
        }
        claims.saveAllAndFlush(theirs);
        boolean hasRate = rates.rateOn(sheetId, grades.gradeOn(person.getId(), day, asOf), day, asOf).isPresent();
        String dueTotal = hasRate || anyFigure ? settings.capped(sheetId, total, asOf).toPlainString() : NO_RATE;
        statements.saveAndFlush(new Statement(sheetId, personId, day, dueTotal, counted, today));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + dueTotal + " claims=" + counted + (asOf != null ? " asof=" + asOf : ""));
        return theirs;
    }

    /** A steward cancels a person's last statement: the claims it bound become live again (a figure
     *  bound by an earlier statement is not restored) and it is no longer counted, though the sheet as it
     *  stood at the end of a day before it was cancelled still shows it. Anyone else, or a
     *  person with no statement, is refused. Returns their claims, newest first. */
    @Transactional
    public List<Claim> cancel(String sheetId, String user, Long personId) {
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + personId + " on sheet " + sheetId));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new StatementCancelRefusedException(user + " is not a steward: only a steward cancels a statement");
        }
        List<Statement> theirs = statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId).stream()
                .filter(Statement::isLive).toList();
        if (theirs.isEmpty()) {
            throw new StatementCancelRefusedException(person.getName() + " has no statement to cancel");
        }
        Statement last = theirs.get(theirs.size() - 1);
        List<Claim> theirClaims = claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, personId);
        List<Claim> bound = theirClaims.stream().filter(Claim::isStated).toList();
        bound.forEach(Claim::unbind);
        claims.saveAllAndFlush(bound);
        // Kept, marked cancelled, so the sheet as it stood at the end of an earlier day still shows it.
        last.cancel(sheets.today(sheetId));
        statements.saveAndFlush(last);
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + last.getDueTotal());
        return theirClaims;
    }
}
