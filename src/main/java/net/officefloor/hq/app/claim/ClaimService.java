package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonStatement;
import net.officefloor.hq.app.person.PersonStatementRepository;
import net.officefloor.hq.app.person.StatementSummary;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final PersonStatementRepository statements;
    private final RateRepository rates;
    private final SheetService sheets;
    private final RollupService rollup;
    private final PeriodService periods;
    private final Audit audit;

    /** A statement just taken: the person, their statement summary now, and their claims, newest first. */
    public record Statement(Person person, StatementSummary summary, List<Claim> claims) {
    }

    public ClaimService(ClaimRepository claims, PersonRepository people, PersonStatementRepository statements,
            RateRepository rates, SheetService sheets, RollupService rollup, PeriodService periods, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.statements = statements;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person on the sheet; a missing date takes the sheet's today. Refused
     *  (423) when the date is in a closed period. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseIfClosed(sheetId, on);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, on, noteOf(note)));
        record("CLAIM_ADDED", user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Change a DRAFT claim's amount, date and note; a missing date takes the sheet's today.
     *  Refused (409) once the claim is no longer a DRAFT (an open claim is voided and a new one
     *  added instead), and (423) when its old or new date is in a closed period. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited");
        }
        refuseIfClosed(sheetId, on);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setDate(on);
        claim.setNote(noteOf(note));
        claims.flush();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        record("CLAIM_EDITED", user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Move a claim to a new status; a move its status does not allow is refused (409), as is
     *  settling by the user who opened the claim (403), and any move of a claim dated in a closed
     *  period (423). */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus to) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canMoveTo(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + to);
        }
        if (to == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        // a voided claim keeps its number (never reused), and the VOIDED record carries it
        Integer number = claim.getNumber();
        claim.setStatus(to);
        if (to == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
            claim.setNumber(number);
            claim.setOpenedBy(user);
        } else if (to == ClaimStatus.SETTLED) {
            // a claim bound by a statement settles at its bound figure
            claim.setSettledDue(claim.isStated() ? claim.getStatedDue() : dueOf(claim, person));
        }
        claims.flush();
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalArgumentException("A claim cannot return to DRAFT");
        };
        if (to == ClaimStatus.SETTLED) {
            BigDecimal due = claim.getSettledDue();
            record(event, user, person, claim, number, " due=" + (due == null ? "n/a" : due.toPlainString()));
        } else {
            record(event, user, person, claim, number);
        }
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Take a statement of a person: record their due-total now, and bind each of their claims
     *  that counts (OPEN or SETTLED) to the due it shows now; the others are left unbound. */
    @Transactional
    public Statement statement(String sheetId, String user, long personId) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate today = sheets.today(sheetId);
        List<Claim> theirs = claims.findByPersonForUpdate(personId, sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> counted = theirs.stream().filter(ClaimService::counts).toList();
        BigDecimal dueTotal = dueTotalOf(counted, person, today);
        for (Claim c : theirs) {
            if (counts(c)) {
                c.bind(shownDueOf(c, person));
            } else {
                c.unbind();
            }
        }
        claims.flush();
        statements.saveAndFlush(new PersonStatement(sheetId, personId, dueTotal, counted.size(), today, user));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counted.size());
        rollup.recordChanges(sheetId, user, before);
        return new Statement(person, StatementSummary.of(statements.findByPersonIdOrderByIdAsc(personId)), theirs);
    }

    /** Refuse (423) a change to a claim dated in a closed period, holding the sheet locked until
     *  the transaction ends so a period cannot be closed meanwhile. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        sheets.lock(sheetId);
        periods.refuseIfClosed(sheetId, date);
    }

    /** Whether a claim counts toward claimed and due totals: only OPEN and SETTLED ones do. */
    private static boolean counts(Claim c) {
        return c.getStatus() == ClaimStatus.OPEN || c.getStatus() == ClaimStatus.SETTLED;
    }

    /** The due a claim shows: the due fixed when it was settled, else the one a statement bound it
     *  to (null: n/a), else its live due. */
    private BigDecimal shownDueOf(Claim claim, Person person) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue() : dueOf(claim, person);
    }

    /** A person's due-total: the sum of the dues their counting claims show (n/a ones skipped);
     *  null (n/a) when their grade has no rate in force on the sheet's today, unless a claim keeps
     *  a fixed or bound due. */
    private BigDecimal dueTotalOf(List<Claim> counted, Person person, LocalDate today) {
        boolean kept = counted.stream()
                .anyMatch(c -> c.getSettledDue() != null || (c.isStated() && c.getStatedDue() != null));
        if (rates.findInForce(person.getSheetId(), person.getGrade(), today).isEmpty() && !kept) {
            return null;
        }
        return counted.stream()
                .map(c -> shownDueOf(c, person))
                .filter(d -> d != null)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** The claim's amount times its person's grade rate in force on the claim's date, divided by
     *  100, rounded half to even; null with no rate in force then. */
    private BigDecimal dueOf(Claim claim, Person person) {
        return rates.findInForce(claim.getSheetId(), person.getGrade(), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                .orElse(null);
    }

    private void record(String event, String user, Person person, Claim claim) {
        record(event, user, person, claim, claim.getNumber());
    }

    /** Amounts are written as the screen shows them (two decimals), dates as yyyy-mm-dd. */
    private void record(String event, String user, Person person, Claim claim, Integer number) {
        record(event, user, person, claim, number, "");
    }

    private void record(String event, String user, Person person, Claim claim, Integer number, String extra) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user
                + " person=" + person.getName() + " amount=" + claim.getAmount().setScale(2).toPlainString()
                + " date=" + claim.getDate() + (number != null ? " number=" + number : "") + extra);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
