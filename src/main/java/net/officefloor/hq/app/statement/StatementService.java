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
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonGradeService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
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
    private final SheetService sheets;
    private final Audit audit;

    public StatementService(StatementRepository statements, ClaimRepository claims, ClaimService claimService,
            PersonRepository people, RateService rates, PersonGradeService grades, SheetService sheets,
            Audit audit) {
        this.statements = statements;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
        this.rates = rates;
        this.grades = grades;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** Each person's statement summary on the sheet, by person id; a person with none is absent. */
    public Map<Long, StatementSummary> summaries(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(Statement::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), StatementSummary::of)));
    }

    public StatementSummary summary(String sheetId, Long personId) {
        return StatementSummary.of(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId));
    }

    /** Take a statement for a person: their claims, newest first, as bound by it. */
    @Transactional
    public List<Claim> take(String sheetId, String user, Long personId) {
        // Lock the sheet, so a claim changed or a statement taken at the same moment waits.
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + personId + " on sheet " + sheetId));
        LocalDate today = sheets.today(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean anyFigure = false;
        int counted = 0;
        for (Claim claim : theirs) {
            if (!claim.countsLive(person.isHeld())) {
                claim.unbind();
                continue;
            }
            // The statement binds the figure; the late charge on top keeps following the sheet's today.
            BigDecimal figure = claimService.figureOf(sheetId, person, claim);
            if (figure != null) {
                total = total.add(claimService.dueOf(sheetId, person, claim));
                anyFigure = true;
            }
            claim.bind(figure);
            counted++;
        }
        claims.saveAllAndFlush(theirs);
        boolean hasRate = rates.rateOn(sheetId, grades.gradeOn(person.getId(), today), today).isPresent();
        String dueTotal = hasRate || anyFigure ? total.toPlainString() : NO_RATE;
        statements.saveAndFlush(new Statement(sheetId, personId, today, dueTotal, counted));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + dueTotal + " claims=" + counted);
        return theirs;
    }
}
