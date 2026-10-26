package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** The statements taken for the people on a sheet. */
@Service
public class StatementService {

    /** A person's statements: how many have been taken, and the last one (null with none). */
    public record Summary(Long personId, int count, Statement last) {
    }

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateRepository rates;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            ClaimService claimService, RateRepository rates, SheetService sheets, RollupService rollup, Audit audit) {
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** Each person's statements on the sheet, for the people who have any. */
    @Transactional(readOnly = true)
    public List<Summary> list(String sheetId) {
        Map<Long, Summary> by = new LinkedHashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            Summary was = by.get(s.getPersonId());
            by.put(s.getPersonId(), new Summary(s.getPersonId(), was == null ? 1 : was.count() + 1, s));
        }
        return List.copyOf(by.values());
    }

    /**
     * Takes a statement for the person: records their due-total now (late charges included) and binds each of their claims that counts to
     * the figure it has now; a claim that does not count (as an open one dated within a hold) is freed (live again).
     */
    @Transactional
    public Summary take(String by, String sheetId, Long personId) {
        Person person = people.findWithLockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        LocalDate today = sheets.load(sheetId).getToday();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean kept = false;
        // the grades whose rate today decides n/a: the one in force today and each counted claim's
        Set<String> grades = new HashSet<>();
        grades.add(person.gradeOn(today));
        int count = 0;
        for (Claim c : claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId)) {
            if (!person.countsLive(c)) {
                c.unbind();
                continue;
            }
            // a settled or already bound claim keeps its figure even with no rate in force today
            grades.add(person.gradeOn(c.getDate()));
            kept |= c.getSettledDue() != null || (c.isBound() && c.getStatedDue() != null);
            // the statement binds the figure under the late charge; the charge keeps following the sheet's today
            BigDecimal due = claimService.due(sheetId, person, c);
            c.bind(claimService.figure(sheetId, person, c));
            count++;
            if (due != null) {
                kept = true;
                total = total.add(due);
            }
        }
        claims.flush();
        // as on screen: with no rate in force today for any of those grades and no claim with a figure, the due-total
        // is n/a
        boolean current = grades.stream().anyMatch(g -> rates.findInForce(sheetId, g, today).isPresent());
        BigDecimal dueTotal = current || kept ? total : null;
        Statement taken = statements.saveAndFlush(new Statement(sheetId, personId, dueTotal, count, today));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + by + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + count);
        rollup.recordChanges(by, sheetId, before);
        return new Summary(personId, statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId).size(),
                taken);
    }
}
