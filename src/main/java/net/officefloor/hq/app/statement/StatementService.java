package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StatementService {

    /** A person's statements: how many were taken, and the last one (none when none was). */
    public record Summary(long personId, int count, Statement last) {
    }

    /** A statement just taken: the person's summary after it, and their claims as it bound them, newest first. */
    public record Taken(Summary summary, List<Claim> claims) {
    }

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateService rates;
    private final SheetService sheets;
    private final Rollup rollup;
    private final Audit audit;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            ClaimService claimService, RateService rates, SheetService sheets, Rollup rollup, Audit audit) {
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The statement summary of each person on the sheet who has had a statement taken. */
    @Transactional(readOnly = true)
    public List<Summary> list(String sheetId) {
        Map<Long, Summary> by = new LinkedHashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            Summary was = by.get(s.getPersonId());
            by.put(s.getPersonId(), new Summary(s.getPersonId(), was == null ? 1 : was.count() + 1, s));
        }
        return List.copyOf(by.values());
    }

    /** Take the person's statement: record their due-total now, bind each of their counting claims to
     *  the figure it shows now, and leave every other claim of theirs unbound. */
    @Transactional
    public Taken take(String sheetId, String user, long personId) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        LocalDate today = sheets.today(sheetId);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findForStatement(sheetId, personId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean keptFigure = false;
        int counting = 0;
        for (Claim claim : theirs) {
            if (!counts(person, claim)) {
                claim.unbind();
                continue;
            }
            counting++;
            // The statement binds the figure; the due-total it records carries the late charge on top.
            BigDecimal figure = claimService.figure(sheetId, person, claim);
            BigDecimal due = claimService.due(sheetId, person, claim);
            if (due != null) total = total.add(due);
            if (claim.getSettledDue() != null || (claim.isBound() && claim.getStatedDue() != null)) keptFigure = true;
            // A live figure at a grade other than today's is not undone by today's grade having no rate.
            if (figure != null && person.gradeOn(claim.getDate()) != person.gradeOn(today)) keptFigure = true;
            // Nor is a live figure on a claim dated by today undone by its rate having ended since.
            if (figure != null && !claim.getDate().isAfter(today)) keptFigure = true;
            claim.bind(figure);
        }
        // As the screen shows it: n/a when no rate of the grade in force today is in force today and no
        // claim keeps a figure.
        BigDecimal dueTotal = rates.inForce(sheetId, person.gradeOn(today), today) == null && !keptFigure ? null : total;
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, dueTotal, counting, today, user));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counting);
        rollup.recordChanges(user, before);
        List<Statement> all = statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        return new Taken(new Summary(personId, all.size(), all.get(all.size() - 1)), theirs);
    }

    /** Only open and settled claims count toward what is claimed and due; a person's open claims dated
     *  within one of their holds do not, unless a statement binds them, as on the screen. */
    private static boolean counts(Person person, Claim claim) {
        return claim.getStatus() == ClaimStatus.SETTLED
                || (claim.getStatus().isOpen() && (claim.isBound() || !person.isHeldOn(claim.getDate())));
    }
}
