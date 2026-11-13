package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The claims people on a sheet make. */
@Service
public class ClaimService {

    /** Written for a due that has no rate to work from. */
    private static final String NO_RATE = "n/a";

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final SheetService sheets;
    private final PeriodRepository periods;
    private final SettingService settings;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateService rates, SheetService sheets,
            PeriodRepository periods, SettingService settings, Audit audit) {
        this.periods = periods;
        this.settings = settings;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a missing date takes the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = person(sheetId, personId);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, on);
        refuseRetired(person, on);
        Claim added = claims.saveAndFlush(new Claim(sheetId, personId, scaled(amount), on, noteOf(note)));
        record("CLAIM_ADDED", sheetId, user, person, added);
        return added;
    }

    /** Change a DRAFT claim's amount, date and note; a missing date takes the sheet's today. Any
     *  other claim is refused: an OPEN one is changed by voiding it and adding a new one. */
    @Transactional
    public Claim update(String sheetId, String user, Long personId, Long id, BigDecimal amount, LocalDate date,
            String note) {
        Person person = person(sheetId, personId);
        Claim claim = claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(claim.getStatus());
        }
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDate());
        refuseClosed(sheetId, on);
        refuseRetired(person, on);
        claim.setAmount(scaled(amount));
        claim.setDate(on);
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_EDITED", sheetId, user, person, saved);
        return saved;
    }

    /** Move a claim to a new status; a change its status does not allow is refused, as is the user
     *  who opened a claim settling it, and a clerk settling a claim whose due is above the clerk
     *  limit. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long personId, Long id, ClaimStatus to) {
        Person person = person(sheetId, personId);
        // Lock the sheet first, so a claim opened at the same moment elsewhere waits and the claim
        // read below is its latest state.
        Long number = to == ClaimStatus.OPEN ? sheets.nextClaimNumber(sheetId) : null;
        Claim claim = claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
        refuseClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canMoveTo(to)) {
            throw new StatusChangeRefusedException(claim.getStatus(), to);
        }
        if (to == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new OwnClaimSettleRefusedException(user);
        }
        // Taken while the claim is still OPEN, so a settled due freezes in the late charge it has
        // gathered by then.
        BigDecimal due = to == ClaimStatus.SETTLED ? dueOf(sheetId, person, claim) : null;
        if (to == ClaimStatus.SETTLED) {
            refuseOverClerkLimit(sheetId, user, due);
        }
        claim.setStatus(to);
        if (to == ClaimStatus.OPEN) {
            claim.setNumber(number);
            claim.setOpenedBy(user);
        } else if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(due);
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException("A claim never returns to DRAFT");
        };
        String line = line(event, sheetId, user, person, saved, saved.getNumber());
        if (to == ClaimStatus.SETTLED) {
            line += " due=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE);
        }
        audit.recordOnCommit(line);
        return saved;
    }

    /** The figure a claim shows: the due fixed when it was settled, else the figure its person's
     *  last statement bound it to, else its due at the person's grade rate; null when it has none. */
    public BigDecimal figureOf(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue() : liveFigureOf(sheetId, person, claim);
    }

    /** The due a claim shows: its figure plus its late charge; null when it has no figure. */
    public BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        BigDecimal figure = figureOf(sheetId, person, claim);
        return figure == null ? null : figure.add(lateChargeOf(sheetId, claim, figure));
    }

    /** The late charge on a claim with a figure: none unless it is OPEN and overdue (its date plus the
     *  terms is before the sheet's today), else the late percent of the figure for every full 30 days
     *  it is overdue, rounded half to even to two decimals. Never bound by a statement: it follows the
     *  sheet's today. */
    private BigDecimal lateChargeOf(String sheetId, Claim claim, BigDecimal figure) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            return none;
        }
        LocalDate dueBy = claim.getDate().plusDays(settings.terms(sheetId));
        long days = ChronoUnit.DAYS.between(dueBy, sheets.today(sheetId));
        if (days < 30) {
            return none;
        }
        return figure.multiply(settings.latePct(sheetId)).multiply(BigDecimal.valueOf(days / 30))
                .movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** The due on a claim at the person's grade rate in force on the claim's date: amount times rate
     *  divided by 100, rounded half to even to two decimals, and at least the sheet's floor; null when
     *  no rate applies. */
    private BigDecimal liveFigureOf(String sheetId, Person person, Claim claim) {
        BigDecimal amount = claim.getAmount();
        return rates.rateOn(sheetId, person.getGrade(), claim.getDate())
                .map(Rate::getRate)
                .map(rate -> amount.multiply(rate).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN))
                .map(due -> due.max(settings.floor(sheetId)))
                .orElse(null);
    }

    /** A clerk cannot settle a claim whose due is above the sheet's clerk limit; a due of n/a is
     *  within any limit, and a steward settles anything. */
    private void refuseOverClerkLimit(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = settings.clerkLimit(sheetId);
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return;
        }
        if (settings.roleOf(sheetId, user) == Role.CLERK) {
            throw new ClerkLimitRefusedException(user, due, limit);
        }
    }

    /** A claim dated inside a closed period cannot be added, edited, opened, settled or voided. */
    private void refuseClosed(String sheetId, LocalDate date) {
        if (periods.closedOn(sheetId, date)) {
            throw new PeriodClosedException("A claim dated " + date + " falls in a closed period");
        }
    }

    /** A claim dated after its person's retirement date cannot be added or moved there. */
    private static void refuseRetired(Person person, LocalDate date) {
        if (person.isRetired() && date.isAfter(person.getRetiredFrom())) {
            throw new PersonRetiredException(person.getName(), person.getRetiredFrom(), date);
        }
    }

    private Person person(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + personId + " on sheet " + sheetId));
    }

    private void record(String event, String sheetId, String user, Person person, Claim claim) {
        record(event, sheetId, user, person, claim, claim.getNumber());
    }

    /** One audit line about a claim; a claim with a number carries it at the end. */
    private void record(String event, String sheetId, String user, Person person, Claim claim, Long number) {
        audit.recordOnCommit(line(event, sheetId, user, person, claim, number));
    }

    private static String line(String event, String sheetId, String user, Person person, Claim claim, Long number) {
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "");
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
