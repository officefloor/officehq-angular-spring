package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonGradeService;
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

    /** The name a claim's period number takes when its date falls in no period. */
    private static final String NO_PERIOD = "X";

    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final PersonRepository people;
    private final RateService rates;
    private final PersonGradeService grades;
    private final SheetService sheets;
    private final PeriodRepository periods;
    private final SettingService settings;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, ClaimLogRepository logs, PersonRepository people, RateService rates,
            PersonGradeService grades, SheetService sheets, PeriodRepository periods, SettingService settings, Audit audit) {
        this.periods = periods;
        this.settings = settings;
        this.claims = claims;
        this.logs = logs;
        this.people = people;
        this.rates = rates;
        this.grades = grades;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    /** Add a claim for a person; a missing date takes the sheet's today. Its due-by is fixed now: its
     *  date plus the terms of the person's grade in force on that date. A negative amount is a
     *  correction and must name a SETTLED claim of the person it corrects. */
    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Person person = person(sheetId, personId);
        corrects = numberOf(corrects);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, on);
        refuseRetired(person, on);
        refuseBadCorrection(sheetId, personId, amount, corrects);
        LocalDate dueBy = on.plusDays(settings.termsFor(sheetId, grades.gradeOn(personId, on)));
        Claim added = claims.saveAndFlush(new Claim(sheetId, personId, scaled(amount), on, dueBy, noteOf(note),
                corrects));
        log(sheetId, user, person, added);
        record("CLAIM_ADDED", sheetId, user, person, added);
        return added;
    }

    /** Change a DRAFT claim's amount, date and note; a missing date takes the sheet's today. Any
     *  other claim is refused: an OPEN one is changed by voiding it and adding a new one. */
    @Transactional
    public Claim update(String sheetId, String user, Long personId, Long id, BigDecimal amount, LocalDate date,
            String note, String corrects) {
        Person person = person(sheetId, personId);
        corrects = numberOf(corrects);
        Claim claim = claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(claim.getStatus());
        }
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDate());
        refuseClosed(sheetId, on);
        refuseRetired(person, on);
        refuseBadCorrection(sheetId, personId, amount, corrects);
        claim.setAmount(scaled(amount));
        claim.setCorrects(corrects);
        claim.setDate(on);
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_EDITED", sheetId, user, person, saved);
        return saved;
    }

    /** Move a claim to a new status; a change its status does not allow is refused, as is the user
     *  who opened a claim settling it. A clerk settling a claim whose due is above the clerk limit
     *  leaves it PENDING a steward's approval instead. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long personId, Long id, ClaimStatus to) {
        Person person = person(sheetId, personId);
        // Lock the sheet first, so a claim opened at the same moment elsewhere waits and the claim
        // read below is its latest state.
        if (to == ClaimStatus.OPEN) {
            sheets.lock(sheetId);
        }
        Claim claim = claim(sheetId, personId, id);
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
        if (to == ClaimStatus.SETTLED && overClerkLimit(sheetId, user, due)) {
            to = ClaimStatus.PENDING;
        }
        claim.setStatus(to);
        if (to == ClaimStatus.OPEN) {
            claim.setNumber(nextNumber(sheetId, claim.getDate()));
            claim.setOpenedBy(user);
        } else if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(due);
        }
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException("A claim never returns to DRAFT");
        };
        String line = line(event, sheetId, user, person, saved, saved.getNumber());
        if (to == ClaimStatus.SETTLED) {
            line += " due=" + settledDueText(saved);
        }
        audit.recordOnCommit(line);
        return saved;
    }

    /** A steward approves a PENDING claim: it is SETTLED, its due frozen at this moment. Anyone else,
     *  the user who opened it, or a claim that is not PENDING, is refused. */
    @Transactional
    public Claim approve(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        Claim claim = pending(sheetId, user, personId, id, ClaimStatus.SETTLED);
        if (user.equals(claim.getOpenedBy())) {
            throw new OwnClaimSettleRefusedException(user);
        }
        claim.setSettledDue(dueOf(sheetId, person, claim));
        claim.setStatus(ClaimStatus.SETTLED);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(line("CLAIM_APPROVED", sheetId, user, person, saved, saved.getNumber())
                + " due=" + settledDueText(saved));
        return saved;
    }

    /** A steward rejects a PENDING claim with a reason: it is OPEN again, bound again by the person's
     *  last statement if that statement bound it. Anyone else, or a claim that is not PENDING, is
     *  refused. */
    @Transactional
    public Claim reject(String sheetId, String user, Long personId, Long id, String reason) {
        Person person = person(sheetId, personId);
        Claim claim = pending(sheetId, user, personId, id, ClaimStatus.OPEN);
        claim.setStatus(ClaimStatus.OPEN);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(line("CLAIM_REJECTED", sheetId, user, person, saved, saved.getNumber())
                + " reason=" + reason.trim());
        return saved;
    }

    /** A steward re-settles a SETTLED claim: its settled due is frozen again at the figure it would
     *  have today as an OPEN claim bound by no statement (today's rates, grades, floor and late
     *  charge), and its status log gains a SETTLED line. Anyone else, a claim that is not SETTLED, or
     *  one dated in a closed period, is refused. */
    @Transactional
    public Claim resettle(String sheetId, String user, Long personId, Long id) {
        Person person = person(sheetId, personId);
        Claim claim = claim(sheetId, personId, id);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResettleRefusedException(user);
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new StatusChangeRefusedException(claim.getStatus(), ClaimStatus.SETTLED);
        }
        refuseClosed(sheetId, claim.getDate());
        String old = settledDueText(claim);
        BigDecimal figure = liveFigureOf(sheetId, person, claim);
        claim.setSettledDue(figure == null ? null : figure.add(lateChargeOf(sheetId, claim, figure, true)));
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(line("CLAIM_RESETTLED", sheetId, user, person, saved, saved.getNumber())
                + " old=" + old + " new=" + settledDueText(saved));
        return saved;
    }

    /** A steward moves a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its
     *  number and date, loses any figure a statement bound it to (live again until its new person's
     *  next statement), and is listed as its new person's newest claim. Anyone else, a SETTLED or VOID
     *  claim, a correction (it names a claim of its own person), a claim dated in a closed period, or
     *  a move to the same person or to one retired before the claim's date, is refused. */
    @Transactional
    public Claim reassign(String sheetId, String user, Long personId, Long id, Long toPersonId) {
        Person from = person(sheetId, personId);
        Person to = person(sheetId, toPersonId);
        Claim claim = claim(sheetId, personId, id);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ReassignRefusedException(user);
        }
        if (!claim.getStatus().canReassign()) {
            throw new MoveRefusedException("A " + claim.getStatus() + " claim cannot move to another person");
        }
        if (from.getId().equals(to.getId())) {
            throw new MoveRefusedException("The claim is already " + to.getName() + "'s");
        }
        if (claim.isCorrection()) {
            throw new MoveRefusedException("A correction stays with the person whose claim it corrects");
        }
        refuseClosed(sheetId, claim.getDate());
        refuseRetired(to, claim.getDate());
        claim.setPersonId(to.getId());
        claim.unbind();
        claims.saveAndFlush(claim);
        claims.relist(claim.getId());
        Claim saved = claim(sheetId, to.getId(), id);
        audit.recordOnCommit(line("CLAIM_REASSIGNED", sheetId, user, to, saved, saved.getNumber())
                + " from=" + from.getName() + " to=" + to.getName());
        return saved;
    }

    /** A claim's status log, oldest first: one line per status it has had. */
    public List<String> logOf(Long claimId) {
        return logs.findByClaimIdOrderByIdAsc(claimId).stream().map(ClaimLogEntry::text).toList();
    }

    /** The status logs of the sheet's claims by claim id, each oldest first. */
    public Map<Long, List<String>> logsOf(String sheetId) {
        return logs.findBySheetIdOrderByIdAsc(sheetId).stream().collect(Collectors.groupingBy(
                ClaimLogEntry::getClaimId, Collectors.mapping(ClaimLogEntry::text, Collectors.toList())));
    }

    /** Log the status a claim has just moved to (or been added in): by whom, on the sheet's today, and
     *  its due as it is now. */
    private void log(String sheetId, String user, Person person, Claim claim) {
        BigDecimal due = dueOf(sheetId, person, claim);
        logs.save(new ClaimLogEntry(sheetId, claim.getId(), claim.getStatus(), user, sheets.today(sheetId),
                due != null ? due.toPlainString() : NO_RATE));
    }

    /** The PENDING claim a steward approves or rejects; refused when it is not PENDING, dated in a
     *  closed period, or the user is not a steward. */
    private Claim pending(String sheetId, String user, Long personId, Long id, ClaimStatus to) {
        Claim claim = claim(sheetId, personId, id);
        refuseClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new StatusChangeRefusedException(claim.getStatus(), to);
        }
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ApprovalRefusedException(user);
        }
        return claim;
    }

    private Claim claim(String sheetId, Long personId, Long id) {
        return claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
    }

    private static String settledDueText(Claim claim) {
        return claim.getSettledDue() != null ? claim.getSettledDue().toPlainString() : NO_RATE;
    }

    /** The number a claim opened now takes: under period numbering (set, and the sheet's today on or
     *  after it) the next within the period its date falls in, <period>-<n>, or X-<n> when it falls
     *  in none; otherwise the sheet's next sheet-wide number. Taken with the sheet locked. */
    private String nextNumber(String sheetId, LocalDate date) {
        LocalDate from = settings.periodNumberingFrom(sheetId);
        if (from == null || sheets.today(sheetId).isBefore(from)) {
            return String.valueOf(sheets.nextClaimNumber(sheetId));
        }
        String prefix = periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(period -> period.contains(date))
                .findFirst().map(Period::getName).orElse(NO_PERIOD) + "-";
        long last = claims.numbers(sheetId).stream()
                .filter(number -> number.startsWith(prefix))
                .map(number -> number.substring(prefix.length()))
                .filter(n -> n.matches("\\d{1,18}"))
                .mapToLong(Long::parseLong)
                .max().orElse(0);
        return prefix + (last + 1);
    }

    /** The figure a claim shows: the due fixed when it was settled, else (unless it is PENDING, which
     *  no statement binds) the figure its person's last statement bound it to, else its due at the
     *  person's grade rate; null when it has none. */
    public BigDecimal figureOf(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() && claim.getStatus() != ClaimStatus.PENDING ? claim.getStatedDue() : liveFigureOf(sheetId, person, claim);
    }

    /** The due a claim shows: its figure plus its late charge; null when it has no figure. */
    public BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        BigDecimal figure = figureOf(sheetId, person, claim);
        return figure == null ? null : figure.add(lateChargeOf(sheetId, claim, figure, claim.getStatus().isOpen()));
    }

    /** The late charge on a claim with a figure: none unless it is OPEN or PENDING and overdue (its due-by is
     *  before the sheet's today) or a correction, else the late percent of the figure for every full 30 days
     *  it is overdue, rounded to two decimals as the claim's date decides (see roundingOf). Never bound by a statement: it follows the
     *  sheet's today. Whether the claim counts as open is given, so a re-settle can charge a SETTLED
     *  claim as if it were still OPEN. */
    private BigDecimal lateChargeOf(String sheetId, Claim claim, BigDecimal figure, boolean open) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (!open || claim.isCorrection()) {
            return none;
        }
        long days = ChronoUnit.DAYS.between(claim.getDueBy(), sheets.today(sheetId));
        if (days < 30) {
            return none;
        }
        return figure.multiply(settings.latePct(sheetId)).multiply(BigDecimal.valueOf(days / 30))
                .movePointLeft(2).setScale(2, roundingOf(sheetId, claim));
    }

    /** The due on a claim at the rate in force on the claim's date for the person's grade in force
     *  on the claim's date: amount times rate
     *  divided by 100, rounded to two decimals as the claim's date decides (see roundingOf), and at least the sheet's floor unless it
     *  is a correction; null when no rate applies. */
    private BigDecimal liveFigureOf(String sheetId, Person person, Claim claim) {
        BigDecimal amount = claim.getAmount();
        RoundingMode rounding = roundingOf(sheetId, claim);
        return rates.rateOn(sheetId, grades.gradeOn(person.getId(), claim.getDate()), claim.getDate())
                .map(Rate::getRate)
                .map(rate -> amount.multiply(rate).movePointLeft(2).setScale(2, rounding))
                .map(due -> claim.isCorrection() ? due : due.max(settings.floor(sheetId)))
                .orElse(null);
    }

    /** The date a claim's live figures round by: its own, or for a correction the date of the claim
     *  it corrects. */
    public LocalDate roundsBy(String sheetId, Claim claim) {
        if (!claim.isCorrection() || claim.getCorrects() == null) {
            return claim.getDate();
        }
        return claims.findBySheetIdAndPersonIdAndNumber(sheetId, claim.getPersonId(), claim.getCorrects())
                .map(Claim::getDate).orElse(claim.getDate());
    }

    /** How a claim's live figures round: by the sheet's policy date against the date it rounds by. */
    private RoundingMode roundingOf(String sheetId, Claim claim) {
        return settings.roundingOn(sheetId, roundsBy(sheetId, claim));
    }

    /** Whether a clerk is settling a claim whose due is above the sheet's clerk limit; a due of n/a
     *  is within any limit, and a steward settles anything. */
    private boolean overClerkLimit(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = settings.clerkLimit(sheetId);
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return false;
        }
        return settings.roleOf(sheetId, user) == Role.CLERK;
    }

    /** A negative amount (a correction) must name the number of a SETTLED claim of the same person;
     *  any other amount names none. */
    private void refuseBadCorrection(String sheetId, Long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new CorrectionRefusedException("Only a negative amount corrects a claim");
            }
            return;
        }
        if (corrects == null) {
            throw new CorrectionRefusedException("A negative amount must name the claim it corrects");
        }
        boolean settled = claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .map(c -> c.getStatus() == ClaimStatus.SETTLED).orElse(false);
        if (!settled) {
            throw new CorrectionRefusedException("No SETTLED claim " + corrects + " of this person to correct");
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
    private void record(String event, String sheetId, String user, Person person, Claim claim, String number) {
        audit.recordOnCommit(line(event, sheetId, user, person, claim, number));
    }

    private static String line(String event, String sheetId, String user, Person person, Claim claim, String number) {
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "");
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** A claim number as typed: trimmed, null when blank. */
    private static String numberOf(String number) {
        return number == null || number.isBlank() ? null : number.trim();
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
