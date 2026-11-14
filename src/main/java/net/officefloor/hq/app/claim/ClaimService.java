package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonStatement;
import net.officefloor.hq.app.person.PersonStatementRepository;
import net.officefloor.hq.app.person.StatementSummary;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    /** The period name a claim in no period is numbered under. */
    private static final String NO_PERIOD = "X";

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final PersonStatementRepository statements;
    private final RateRepository rates;
    private final SheetService sheets;
    private final RollupService rollup;
    private final PeriodService periods;
    private final SettingsService settings;
    private final Audit audit;

    /** A statement just taken: the person, their statement summary now, and their claims, newest first. */
    public record Statement(Person person, StatementSummary summary, List<Claim> claims) {
    }

    public ClaimService(ClaimRepository claims, PersonRepository people, PersonStatementRepository statements,
            RateRepository rates, SheetService sheets, RollupService rollup, PeriodService periods, SettingsService settings, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.statements = statements;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** The claims of the ids on the sheet in list order: the people in the order they were added,
     *  each person's claims newest first; ids not on the sheet are left out. */
    @Transactional(readOnly = true)
    public List<Long> inListOrder(String sheetId, Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> theirs = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < theirs.size(); i++) {
            personOrder.put(theirs.get(i).getId(), i);
        }
        return claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> wanted.contains(c.getId()))
                .sorted(Comparator.comparingInt(c -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE)))
                .map(Claim::getId)
                .toList();
    }

    /** Add a claim for a person on the sheet; a missing date takes the sheet's today. Refused
     *  (423) when the date is in a closed period, (422) when it is after the person's
     *  retirement date, and (422) when it is a correction (a negative amount) that does not name
     *  the number of a SETTLED claim of the same person. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseIfClosed(sheetId, on);
        refuseIfRetired(person, on);
        String corrected = correctedBy(sheetId, personId, amount, corrects);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim fresh = new Claim(sheetId, personId, amount, on, noteOf(note));
        fresh.setCorrects(corrected);
        Claim claim = claims.saveAndFlush(fresh);
        record("CLAIM_ADDED", user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Change a DRAFT claim's amount, date and note; a missing date takes the sheet's today.
     *  Refused (409) once the claim is no longer a DRAFT (an open claim is voided and a new one
     *  added instead), and (423) when its old or new date is in a closed period, and (422) when the new
     *  date is after the person's retirement date, or when it is a correction that does not name
     *  the number of a SETTLED claim of the same person. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited");
        }
        refuseIfClosed(sheetId, on);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        refuseIfRetired(person, on);
        String corrected = correctedBy(sheetId, person.getId(), amount, corrects);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setCorrects(corrected);
        claim.setDate(on);
        claim.setNote(noteOf(note));
        claims.flush();
        record("CLAIM_EDITED", user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Move a claim to a new status; a move its status does not allow is refused (409), as is
     *  settling by the user who opened the claim (403), settling by a clerk a claim
     *  whose due is above the sheet's clerk limit (403), and any move of a claim dated in a closed
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
        // a claim bound by a statement settles at its bound figure, its late charge frozen in
        BigDecimal settling = to == ClaimStatus.SETTLED ? shownDueOf(claim, person) : null;
        if (to == ClaimStatus.SETTLED && !settings.maySettle(sheetId, user, settling)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A clerk cannot settle a claim whose due is above the clerk limit");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        // a voided claim keeps its number (never reused), and the VOIDED record carries it
        String number = claim.getNumber();
        claim.setStatus(to);
        if (to == ClaimStatus.OPEN) {
            number = nextNumber(sheetId, claim.getDate());
            claim.setNumber(number);
            claim.setOpenedBy(user);
        } else if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(settling);
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
     *  that counts (OPEN or SETTLED, of a held person only SETTLED) to its figure now; the others
     *  are left unbound. */
    @Transactional
    public Statement statement(String sheetId, String user, long personId) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate today = sheets.today(sheetId);
        List<Claim> theirs = claims.findByPersonForUpdate(personId, sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> counted = theirs.stream().filter(c -> counts(c, person)).toList();
        BigDecimal dueTotal = dueTotalOf(counted, person, today);
        for (Claim c : theirs) {
            if (counts(c, person)) {
                // the statement binds the figure; the late charge keeps following the sheet's today
                c.bind(c.getSettledDue() != null ? c.getSettledDue() : figureOf(c, person));
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

    /** The number a claim dated {@code date} takes as it is opened: under period numbering (set,
     *  and the sheet's today on or after it) the next within the period containing the date
     *  (<period name>-<n>), or X-<n> when none does; otherwise the sheet's next number. */
    private String nextNumber(String sheetId, LocalDate date) {
        if (!sheets.periodNumberingOn(sheetId)) {
            return String.valueOf(sheets.nextClaimNumber(sheetId));
        }
        // held until the transaction ends, so two claims opened at once are numbered one after the other
        sheets.lock(sheetId);
        String prefix = periods.containing(sheetId, date).map(Period::getName).orElse(NO_PERIOD) + "-";
        int last = claims.findNumbers(sheetId).stream()
                .filter(n -> n.startsWith(prefix))
                .map(n -> n.substring(prefix.length()))
                .filter(n -> n.matches("\\d{1,9}"))
                .mapToInt(Integer::parseInt)
                .max().orElse(0);
        return prefix + (last + 1);
    }

    /** Refuse (423) a change to a claim dated in a closed period, holding the sheet locked until
     *  the transaction ends so a period cannot be closed meanwhile. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        sheets.lock(sheetId);
        periods.refuseIfClosed(sheetId, date);
    }

    /** The number a claim of the amount corrects: none unless the amount is negative, when it must
     *  be the number of a SETTLED claim of the same person, or the claim is refused (422). */
    private String correctedBy(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            return null;
        }
        if (corrects == null
                || !claims.existsBySheetIdAndPersonIdAndNumberAndStatus(sheetId, personId, corrects, ClaimStatus.SETTLED)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A negative amount must correct a settled claim of the same person");
        }
        return corrects;
    }

    /** Refuse (422) a claim dated after its person's retirement date. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (person.retiredBefore(date)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The date is after the person's retirement date");
        }
    }

    /** Whether a claim counts toward claimed and due totals: only OPEN and SETTLED ones do, and
     *  of a held person only SETTLED ones. */
    private static boolean counts(Claim c, Person person) {
        return c.getStatus() == ClaimStatus.SETTLED
                || (c.getStatus() == ClaimStatus.OPEN && !person.isHeld());
    }

    /** The due a claim shows: the due fixed when it was settled, else its figure with its late
     *  charge on top. */
    private BigDecimal shownDueOf(Claim claim, Person person) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return LateCharge.of(sheets.find(claim.getSheetId())).addTo(claim, figureOf(claim, person), roundingOf(claim));
    }

    /** A claim's figure before any late charge: the one a statement bound it to (null: n/a), else
     *  its live due. */
    private BigDecimal figureOf(Claim claim, Person person) {
        return claim.isStated() ? claim.getStatedDue() : dueOf(claim, person);
    }

    /** A person's due-total: the sum of the dues their counting claims show (n/a ones skipped);
     *  null (n/a) when their grade in force on the sheet's today has no rate then, unless a claim
     *  shows a due (a fixed or bound one, or one at the rate of the grade they had on its date). */
    private BigDecimal dueTotalOf(List<Claim> counted, Person person, LocalDate today) {
        List<BigDecimal> dues = counted.stream().map(c -> shownDueOf(c, person)).filter(d -> d != null).toList();
        if (rates.findInForce(person.getSheetId(), person.gradeOn(today), today).isEmpty() && dues.isEmpty()) {
            return null;
        }
        return dues.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** The claim's amount times the rate, in force on the claim's date, of its person's grade then, divided by
     *  100, rounded as its rounding policy has it, raised to the sheet's floor (a correction's never is); null with no rate in force then. */
    private BigDecimal dueOf(Claim claim, Person person) {
        BigDecimal floor = sheets.floor(claim.getSheetId());
        return rates.findInForce(claim.getSheetId(), person.gradeOn(claim.getDate()), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, roundingOf(claim)))
                .map(due -> claim.isCorrection() ? due : due.max(floor))
                .orElse(null);
    }

    /** How the claim's live figures round: by the sheet's policy date against its date, or for a
     *  correction the date of the claim it corrects. */
    private RoundingMode roundingOf(Claim claim) {
        LocalDate on = claim.getCorrects() == null ? claim.getDate()
                : claims.findFirstBySheetIdAndPersonIdAndNumber(claim.getSheetId(), claim.getPersonId(), claim.getCorrects())
                        .map(Claim::getDate).orElse(claim.getDate());
        return Rounding.of(sheets.find(claim.getSheetId())).on(on);
    }

    private void record(String event, String user, Person person, Claim claim) {
        record(event, user, person, claim, claim.getNumber());
    }

    /** Amounts are written as the screen shows them (two decimals), dates as yyyy-mm-dd. */
    private void record(String event, String user, Person person, Claim claim, String number) {
        record(event, user, person, claim, number, "");
    }

    private void record(String event, String user, Person person, Claim claim, String number, String extra) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user
                + " person=" + person.getName() + " amount=" + claim.getAmount().setScale(2).toPlainString()
                + " date=" + claim.getDate() + (number != null ? " number=" + number : "") + extra);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
