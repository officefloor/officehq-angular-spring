package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A person's statements: taking one records their due-total on the sheet's today, binds each of
 * their counting claims to the due figure it shows then, and leaves the rest unbound.
 */
@Service
public class StatementService {

    /** Shown in place of a due-total when the grade has no rate. */
    private static final String NO_RATE = "n/a";

    /** The person as stated, with all of their claims, newest first. */
    public record Stated(Person person, List<Claim> claims) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateRepository rates;
    private final SheetService sheets;
    private final Rollups rollups;
    private final Audit audit;

    public StatementService(PersonRepository people, ClaimRepository claims, ClaimService claimService,
            RateRepository rates, SheetService sheets, Rollups rollups, Audit audit) {
        this.rollups = rollups;
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** Take a statement for a person on the sheet. */
    @Transactional
    public Stated take(String sheetId, String user, long personId) {
        LocalDate today = sheets.today(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        Rollups.Rollup before = rollups.take(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean figured = false;
        int counted = 0;
        for (Claim claim : theirs) {
            // As in the live totals: an OPEN or PENDING claim dated within a hold, and not bound, is left
            // out, as is a claim deferred on the sheet's today.
            if (!person.countsLive(claim.getStatus(), claim.getDate(), claim.isStated(), claim.isDeferredOn(today))) {
                claim.unbind();
                continue;
            }
            // The statement binds the rate figure; the late charge and floor stay live, but count in its total.
            BigDecimal figure = claimService.figure(claim, person.gradeOn(claim.getDate()));
            figured |= figure != null;
            claim.bind(figure);
            counted++;
            BigDecimal due = claimService.due(claim, person.gradeOn(claim.getDate()));
            if (due != null) {
                total = total.add(due);
            }
        }
        // As on the screen: n/a when the grade in force today has no rate, unless a claim has a figure.
        if (rates.findInForce(sheetId, person.gradeOn(today), today).isEmpty() && !figured) {
            total = null;
        }
        // A due-total is at most the sheet's cap.
        total = sheets.load(sheetId).capped(total);
        person.stated(total, today);
        Person saved = people.saveAndFlush(person);
        List<Claim> bound = claims.saveAllAndFlush(theirs);
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " due-total=" + (total != null ? total.toPlainString() : NO_RATE) + " claims=" + counted);
        rollups.audit(sheetId, user, before);
        return new Stated(saved, bound);
    }
}
