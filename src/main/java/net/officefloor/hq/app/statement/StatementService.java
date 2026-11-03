package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Statements on a sheet. Taking a person's statement records their due-total at that moment, taken
 * on the sheet's today, and binds every claim of theirs that counts (OPEN, PENDING or SETTLED, but not a held
 * person's OPEN or PENDING claims) to the figure it has then; a claim of theirs that does not count is unbound,
 * so its figure is live again. The late charge is never bound: the due-total includes each claim's charge on the day, but a claim is
 * bound to the figure under it.
 */
@Service
public class StatementService {

    /** A person's statements: how many, and the last one (none before the first). */
    public record Summary(long personId, int count, Statement last) {
    }

    /** A statement just taken, with the person's claims as it left them. */
    public record Taken(Summary summary, List<Claim> claims) {
    }

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateRepository rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            ClaimService claimService, RateRepository rates, SheetService sheets, Audit audit, RollupService rollup) {
        this.rollup = rollup;
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** Each person's statements on the sheet who has taken any. */
    @Transactional(readOnly = true)
    public List<Summary> list(String sheetId) {
        Map<Long, Summary> by = new LinkedHashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            Summary was = by.get(s.getPersonId());
            by.put(s.getPersonId(), new Summary(s.getPersonId(), was == null ? 1 : was.count() + 1, s));
        }
        return List.copyOf(by.values());
    }

    @Transactional
    public Taken take(String sheetId, String user, long personId) {
        Person person = people.lockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        LocalDate today = sheets.today(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean anyFigure = false;
        int counted = 0;
        for (Claim claim : theirs) {
            if (person.counts(claim.getStatus())) {
                BigDecimal figure = claimService.figure(sheetId, person, claim);
                BigDecimal charged = claimService.charged(sheetId, person, claim);
                claim.bind(true, figure);
                if (charged != null) {
                    total = total.add(charged);
                    anyFigure = true;
                }
                counted++;
            } else {
                claim.bind(false, null);
            }
        }
        claims.saveAllAndFlush(theirs);
        // as on screen: n/a when the grade has no rate today and no claim that counts has a figure
        BigDecimal dueTotal = anyFigure || rates.findInForce(sheetId, person.gradeOn(today), today).isPresent() ? total
                : null;
        Statement taken = statements.saveAndFlush(new Statement(sheetId, personId, today, dueTotal, counted));
        int count = (int) statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(s -> Objects.equals(s.getPersonId(), personId)).count();
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + money(dueTotal) + " claims=" + counted);
        rollup.recordChanges(sheetId, user, before);
        return new Taken(new Summary(personId, count, taken), theirs);
    }

    public static String money(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
