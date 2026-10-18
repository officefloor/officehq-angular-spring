package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.Statement;
import net.officefloor.hq.app.person.StatementRepository;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final StatementRepository statements;
    private final SheetService sheets;
    private final RollupService rollups;
    private final PeriodService periods;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates,
            StatementRepository statements, SheetService sheets, RollupService rollups, PeriodService periods,
            Audit audit) {
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.statements = statements;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /**
     * Add a claim for a person; without a date it is dated the sheet's today. One dated in a closed
     * period is refused.
     */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        sheets.lock(sheetId, user);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate dated = dateOrToday(sheetId, date);
        periods.requireOpenOn(sheetId, dated);
        requireNotRetired(person, dated);
        RollupService.Rollup before = rollups.snapshot(sheetId);
        Claim saved = claims.saveAndFlush(new Claim(sheetId, personId, amount, dated, noteOf(note)));
        audit(sheetId, user, "CLAIM_ADDED", person, saved, saved.getNumber());
        rollups.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Change a draft claim's amount, date and note; without a date it is dated the sheet's today. A
     * claim that is no longer a draft is refused (an open one is voided and a new one added instead),
     * as is one dated in a closed period, or moved into one.
     */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        sheets.lock(sheetId, user);
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited");
        }
        LocalDate dated = dateOrToday(sheetId, date);
        periods.requireOpenOn(sheetId, claim.getDate());
        periods.requireOpenOn(sheetId, dated);
        Person owner = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        requireNotRetired(owner, dated);
        RollupService.Rollup before = rollups.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setDate(dated);
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        audit(sheetId, user, "CLAIM_EDITED", person, saved, saved.getNumber());
        rollups.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Move a claim to its next status; a change its current status does not allow is refused, as is
     * settling it by the user who opened it, and any change to a claim dated in a closed period.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus next) {
        Sheet sheet = sheets.lock(sheetId, user);
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        periods.requireOpenOn(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot settle it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Rollup before = rollups.snapshot(sheetId);
        Integer number = next == ClaimStatus.OPEN ? Integer.valueOf(sheet.takeClaimNumber()) : claim.getNumber();
        claim.setStatus(next);
        claim.setNumber(number);
        if (next == ClaimStatus.OPEN) {
            claim.setOpenedBy(user);
        }
        if (next == ClaimStatus.SETTLED) {
            claim.setDue(figure(sheetId, person, claim));
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            default -> "CLAIM_VOIDED";
        };
        String suffix = next == ClaimStatus.SETTLED
                ? " due=" + (saved.getDue() == null ? "n/a" : saved.getDue().setScale(2).toPlainString())
                : "";
        audit(sheetId, user, event, person, saved, number, suffix);
        rollups.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Take a statement for a person on the sheet's today: their due-total then (the sum of the figures
     * of their claims that count; n/a when their grade has no rate in force today and none of those
     * claims has a fixed or bound figure) is recorded, and each claim that counts is bound to its
     * figure then, the others left unbound. Their claims, newest first.
     */
    @Transactional
    public List<Claim> statement(String sheetId, String user, long personId) {
        Sheet sheet = sheets.lock(sheetId, user);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Rollup before = rollups.snapshot(sheetId);
        List<Claim> theirs = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> c.getPersonId().equals(personId))
                .toList();
        List<Claim> counting = theirs.stream().filter(c -> c.getStatus().counts()).toList();
        boolean anyFixed = counting.stream().anyMatch(c -> c.getDue() != null || c.isStated());
        boolean rated = rates.findInForce(sheetId, person.getGrade(), sheet.getToday()).isPresent();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim c : theirs) {
            if (c.getStatus().counts()) {
                BigDecimal figure = figure(sheetId, person, c);
                if (figure != null) total = total.add(figure);
                c.bind(figure);
            } else {
                c.unbind();
            }
        }
        claims.saveAllAndFlush(theirs);
        BigDecimal dueTotal = rated || anyFixed ? total : null;
        statements.saveAndFlush(new Statement(sheetId, personId, sheet.getToday(), dueTotal, counting.size(), user));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString())
                + " claims=" + counting.size());
        rollups.recordChanges(sheetId, user, before);
        return theirs;
    }

    /** The figure a claim shows (see {@link RollupService#figure}); null when none. */
    private BigDecimal figure(String sheetId, Person person, Claim claim) {
        return rollups.figure(sheetId, person, claim);
    }

    /**
     * Amounts are written as the screen shows them (two decimals), dates as yyyy-mm-dd; a claim
     * with a number (a voided one keeps it) ends with it.
     */
    private void audit(String sheetId, String user, String event, Person person, Claim claim, Integer number) {
        audit(sheetId, user, event, person, claim, number, "");
    }

    /** As above, with {@code suffix} (fields a later change added) at the end of the line. */
    private void audit(String sheetId, String user, String event, Person person, Claim claim, Integer number,
            String suffix) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "") + suffix);
    }

    /** Refuse a claim dated after the person's retirement. */
    private static void requireNotRetired(Person person, LocalDate date) {
        if (person.retiredOn(date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That claim is dated after the person retired");
        }
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
