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
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The statements taken of people on a sheet. */
@Service
public class StatementService {

    /** What a person's statements come to: how many, and the last one's due-total and date. */
    public record Summary(long personId, int count, String dueTotal, LocalDate takenOn) {
    }

    /** A statement just taken: the person's summary and their claims, newest first, as it left them. */
    public record Taken(Summary summary, List<Claim> claims) {
    }

    private final StatementRepository statements;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;
    private final Rollup rollup;

    public StatementService(StatementRepository statements, ClaimRepository claims, PersonRepository people,
            RateRepository rates, SheetService sheets, SheetRepository sheetStore, Audit audit, Rollup rollup) {
        this.statements = statements;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** The summary of each person on the sheet who has had a statement taken. */
    @Transactional(readOnly = true)
    public List<Summary> list(String sheetId) {
        Map<Long, Summary> byPerson = new LinkedHashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            Summary before = byPerson.get(s.getPersonId());
            byPerson.put(s.getPersonId(),
                    new Summary(s.getPersonId(), before == null ? 1 : before.count() + 1, s.getDueTotal(), s.getTakenOn()));
        }
        return List.copyOf(byPerson.values());
    }

    /**
     * Take a statement of a person: record their due-total now, bind each of their claims that counts
     * to the figure it has now (its settled figure, else the figure it is bound to, else its amount at
     * its grade's rate in force on its date) and leave the rest unbound. The sheet is locked first, so
     * a statement is not taken while a claim on it changes.
     */
    @Transactional
    public Taken take(String sheetId, String user, long personId) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        LocalDate today = sheet.getToday();
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        Rollup.Figures before = rollup.of(sheetId);
        List<Rate> gradeRates = rates.findBySheetIdAndGrade(sheetId, person.getGrade());
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);

        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean anyFixed = false;
        int counted = 0;
        for (Claim claim : theirs) {
            if (!claim.getStatus().counts()) {
                claim.unbind();
                continue;
            }
            BigDecimal fixed = claim.getSettledDue() != null ? claim.getSettledDue() : claim.getStatedDue();
            BigDecimal figure = fixed != null ? fixed
                    : Rate.inForceOn(gradeRates, claim.getDate())
                            .map(rate -> ClaimService.floored(ClaimService.dueOn(claim.getAmount(), rate.getRate()),
                                    sheet.getFloor()))
                            .orElse(null);
            anyFixed |= fixed != null;
            total = figure == null ? total : total.add(figure);
            claim.bind(figure);
            counted++;
        }
        // As on screen: with no rate in force today and no fixed figure, the person has no due-total.
        String dueTotal = Rate.inForceOn(gradeRates, today).isEmpty() && !anyFixed ? "n/a" : total.toPlainString();
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, dueTotal, today));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + dueTotal + " claims=" + counted);
        rollup.recordChanges(sheetId, user, before);
        List<Statement> taken = statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        return new Taken(new Summary(personId, taken.size(), dueTotal, today), theirs);
    }
}
