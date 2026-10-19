package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.rollup.RollupService.Snapshot;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StatementService {

    /** What a person's statements come to: how many, and the last one (none before the first). */
    public record Summary(int count, Statement last) {}

    private final StatementRepository statements;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PersonRepository people;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public StatementService(StatementRepository statements, ClaimRepository claims, ClaimService claimService,
            PersonRepository people, SheetService sheets, RollupService rollup, Audit audit) {
        this.statements = statements;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Each person's statements on the sheet, by person id. */
    @Transactional(readOnly = true)
    public Map<Long, Summary> summaries(String sheetId) {
        Map<Long, Summary> by = new HashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            Summary was = by.get(s.getPersonId());
            by.put(s.getPersonId(), new Summary(was == null ? 1 : was.count() + 1, s));
        }
        return by;
    }

    /**
     * Take a statement for a person: record their due-total now, late charges included, and bind each of their
     * claims that counts (a held person's OPEN and PENDING ones do not) to the figure it has now (never its late charge, which keeps following the sheet's
     * today); a claim of theirs that does not count is left unbound.
     */
    @Transactional
    public Person take(String user, String sheetId, Long personId) {
        LocalDate today = sheets.today(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Snapshot before = rollup.snapshot(sheetId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean fixed = false;
        List<Claim> counted = new ArrayList<>();
        for (Claim claim : claims.findBySheetIdAndPersonId(sheetId, personId)) {
            if (!counts(claim, person)) {
                claim.unbind();
                continue;
            }
            BigDecimal figure = claimService.figureOf(claim, person);
            BigDecimal due = rollup.dueOf(claim, person);
            fixed |= RollupService.keepsFigure(claim);
            claim.bind(figure);
            counted.add(claim);
            if (due != null) {
                total = total.add(due);
            }
        }
        // Like the screen: with no rate in force today and no claim keeping a figure, the due-total is n/a.
        boolean rated = rollup.rated(person, counted, today);
        BigDecimal dueTotal = rated || fixed ? total : null;
        claims.flush();
        statements.saveAndFlush(new Statement(sheetId, personId, today, user, dueTotal, counted.size()));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal != null ? dueTotal.toPlainString() : "n/a")
                + " claims=" + counted.size());
        rollup.recordChanges(user, sheetId, before);
        return person;
    }

    /** Only OPEN, PENDING and SETTLED claims count toward what is claimed and due; a held person's OPEN and
     * PENDING ones do not. */
    private static boolean counts(Claim claim, Person person) {
        return (claim.getStatus().isOpen() && !person.isHeld())
                || claim.getStatus() == ClaimStatus.SETTLED;
    }
}
