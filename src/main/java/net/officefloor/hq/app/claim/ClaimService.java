package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.Statement;
import net.officefloor.hq.app.person.StatementRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    /** The prefix of the numbers of claims in no period, while the sheet numbers by period. */
    private static final String NO_PERIOD = "X";

    private final ClaimRepository claims;
    private final ClaimNumberSequenceRepository sequences;
    private final PersonRepository people;
    private final RateRepository rates;
    private final StatementRepository statements;
    private final SheetService sheets;
    private final RollupService rollups;
    private final PeriodService periods;
    private final SettingsService settings;
    private final Audit audit;
    private final TransactionTemplate transactions;

    public ClaimService(ClaimRepository claims, ClaimNumberSequenceRepository sequences, PersonRepository people, RateRepository rates,
            StatementRepository statements, SheetService sheets, RollupService rollups, PeriodService periods,
            SettingsService settings, Audit audit, PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.periods = periods;
        this.settings = settings;
        this.claims = claims;
        this.sequences = sequences;
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
     * period is refused, as is a correction (a negative amount) that does not name a settled claim of
     * the same person.
     */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        return add(sheetId, user, personId, amount, date, note, null);
    }

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Sheet sheet = sheets.lock(sheetId, user);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate dated = dateOrToday(sheetId, date);
        periods.requireOpenOn(sheetId, dated);
        requireNotRetired(person, dated);
        String corrected = correctedNumber(sheetId, personId, amount, corrects);
        LocalDate dueBy = dated.plusDays(sheet.termsFor(person.gradeOn(dated)));
        RollupService.Rollup before = rollups.snapshot(sheetId);
        Claim saved = claims.saveAndFlush(
                new Claim(sheetId, personId, amount, dated, dueBy, noteOf(note), corrected));
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
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
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
        String corrected = correctedNumber(sheetId, owner.getId(), amount, corrects);
        RollupService.Rollup before = rollups.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setCorrects(corrected);
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
     * Move a claim to its next status; a change its current status does not allow is refused (a
     * pending claim is approved or rejected instead), as is settling it by the user who opened it, and
     * any change to a claim dated in a closed period. A clerk settling a claim whose due is above the
     * sheet's clerk limit makes it PENDING instead: it waits
     * for a steward to approve or reject it.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus next) {
        Sheet sheet = sheets.lock(sheetId, user);
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        periods.requireOpenOn(sheetId, claim.getDate());
        if (claim.getStatus() == ClaimStatus.PENDING || !claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot settle it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (next == ClaimStatus.SETTLED && needsApproval(sheet, user, person, claim)) {
            next = ClaimStatus.PENDING;
        }
        RollupService.Rollup before = rollups.snapshot(sheetId);
        String number = next == ClaimStatus.OPEN ? nextNumber(sheet, claim) : claim.getNumber();
        // Worked out while the claim is still open, so settling freezes its late charge in.
        BigDecimal settledDue = next == ClaimStatus.SETTLED ? figure(sheetId, person, claim) : null;
        claim.setStatus(next);
        claim.setNumber(number);
        if (next == ClaimStatus.OPEN) {
            claim.setOpenedBy(user);
        }
        if (next == ClaimStatus.SETTLED) {
            claim.setDue(settledDue);
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            default -> "CLAIM_VOIDED";
        };
        String suffix = next == ClaimStatus.SETTLED ? dueField(saved) : "";
        audit(sheetId, user, event, person, saved, number, suffix);
        rollups.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward approves a pending claim: it is settled, its due fixed at its (live) figure now. One
     * that is not pending is refused, as is approval by anyone but a steward, by the user who opened
     * it, or of a claim dated in a closed period.
     */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        Sheet sheet = sheets.lock(sheetId, user);
        Claim claim = pending(sheet, user, claimId);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot approve it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Rollup before = rollups.snapshot(sheetId);
        BigDecimal settledDue = figure(sheetId, person, claim);
        claim.setStatus(ClaimStatus.SETTLED);
        claim.setDue(settledDue);
        Claim saved = claims.saveAndFlush(claim);
        audit(sheetId, user, "CLAIM_APPROVED", person, saved, saved.getNumber(), dueField(saved));
        rollups.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward rejects a pending claim, giving a reason: it is open again (bound again by its
     * person's last statement when that statement included it). One that is not pending is refused, as
     * is a rejection by anyone but a steward, or of a claim dated in a closed period.
     */
    @Transactional
    public Claim reject(String sheetId, String user, long claimId, String reason) {
        Sheet sheet = sheets.lock(sheetId, user);
        Claim claim = pending(sheet, user, claimId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Rollup before = rollups.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        Claim saved = claims.saveAndFlush(claim);
        audit(sheetId, user, "CLAIM_REJECTED", person, saved, saved.getNumber(), " reason=" + reason);
        rollups.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward moves a draft, open or pending claim to another person on the sheet: it keeps its
     * number and date and loses any figure a statement bound it to (live again until its new person's
     * next statement). A settled or voided claim is refused, as is a move by anyone but a steward, to
     * the person it already belongs to, to a person retired by its date, of a correction to a person
     * without the settled claim it corrects, or of a claim dated in a closed period.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long toPersonId) {
        sheets.lock(sheetId, user);
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward moves a claim");
        }
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + claim.getStatus() + " claim cannot move");
        }
        periods.requireOpenOn(sheetId, claim.getDate());
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Person to = people.findByIdAndSheetId(toPersonId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already that person's");
        }
        requireNotRetired(to, claim.getDate());
        correctedNumber(sheetId, to.getId(), claim.getAmount(), claim.getCorrects());
        RollupService.Rollup before = rollups.snapshot(sheetId);
        claim.setPersonId(to.getId());
        claim.unbind();
        Claim saved = claims.saveAndFlush(claim);
        audit(sheetId, user, "CLAIM_REASSIGNED", to, saved, saved.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollups.recordChanges(sheetId, user, before);
        return saved;
    }

    /** The pending claim a steward acts on; refused when it is not pending, not dated in an open period, or the user is no steward. */
    private Claim pending(Sheet sheet, String user, long claimId) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheet.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        periods.requireOpenOn(sheet.getId(), claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + claim.getStatus() + " claim is not pending");
        }
        if (settings.roleOf(sheet.getId(), user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward approves or rejects a claim");
        }
        return claim;
    }

    /** A settled claim's due field, for the end of its audit line. */
    private static String dueField(Claim claim) {
        return " due=" + (claim.getDue() == null ? "n/a" : claim.getDue().setScale(2).toPlainString());
    }

    /** How many of the claims asked for a bulk open were opened, and how many were refused. */
    public record BulkOpened(int opened, int refused) {
    }

    /**
     * Open each of these claims that can be opened, numbering them in list order (the people's order,
     * then their claims as listed, newest first); each that cannot is refused and left as it is.
     * Each is opened in a transaction of its own, so a refused one rolls back only itself.
     */
    public BulkOpened bulkOpen(String sheetId, String user, Collection<Long> claimIds) {
        Set<Long> wanted = new HashSet<>(claimIds);
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) personOrder.put(listed.get(i).getId(), i);
        List<Claim> chosen = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> wanted.contains(c.getId()))
                .sorted(Comparator.<Claim>comparingInt(c -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getId, Comparator.reverseOrder()))
                .toList();
        int opened = 0;
        for (Claim c : chosen) {
            try {
                transactions.executeWithoutResult(t -> changeStatus(sheetId, user, c.getId(), ClaimStatus.OPEN));
                opened++;
            } catch (ResponseStatusException refused) {
                // Left as it is: every check comes before any change.
            }
        }
        return new BulkOpened(opened, wanted.size() - opened);
    }

    /**
     * Take a statement for a person on the sheet's today: their due-total then (the sum of the figures
     * of their claims that count, a held person's open ones not among them; n/a when the grade in force
     * today has no rate in force then and none of those claims has a figure; late charges in) is recorded, and each claim that counts is bound to its
     * figure then (its late charge not bound: it keeps following the sheet's today), the others left unbound. Their claims, newest first.
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
        List<Claim> counting = theirs.stream().filter(c -> RollupService.counts(person, c)).toList();
        boolean rated = rates.findInForce(sheetId, person.gradeOn(sheet.getToday()), sheet.getToday()).isPresent();
        boolean anyFigure = false;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim c : theirs) {
            if (RollupService.counts(person, c)) {
                BigDecimal figure = figure(sheetId, person, c);
                if (figure != null) total = total.add(figure);
                anyFigure |= figure != null;
                c.bind(rollups.boundFigure(sheetId, person, c));
            } else {
                c.unbind();
            }
        }
        claims.saveAllAndFlush(theirs);
        BigDecimal dueTotal = rated || anyFigure ? total : null;
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
    private void audit(String sheetId, String user, String event, Person person, Claim claim, String number) {
        audit(sheetId, user, event, person, claim, number, "");
    }

    /** As above, with {@code suffix} (fields a later change added) at the end of the line. */
    private void audit(String sheetId, String user, String event, Person person, Claim claim, String number,
            String suffix) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "") + suffix);
    }

    /**
     * The number a claim being opened gets: while the sheet numbers by period (its period numbering
     * from-date is set and its today is on or after it), the next in the sequence of the period the
     * claim's date falls in, as {@code <period name>-<n>}, or {@code X-<n>} for one in no period; else
     * the next sheet-wide number.
     */
    private String nextNumber(Sheet sheet, Claim claim) {
        if (!sheet.numbersByPeriod()) return String.valueOf(sheet.takeClaimNumber());
        String prefix = periods.periodOn(sheet.getId(), claim.getDate()).map(Period::getName).orElse(NO_PERIOD);
        ClaimNumberSequence sequence = sequences.findBySheetIdAndPrefix(sheet.getId(), prefix)
                .orElseGet(() -> new ClaimNumberSequence(sheet.getId(), prefix));
        int n = sequence.take();
        sequences.saveAndFlush(sequence);
        return prefix + "-" + n;
    }

    /**
     * Whether a settle needs a steward's approval: a clerk settling a claim whose due is above the
     * sheet's clerk limit (one with no due, n/a, is not above it); a steward settles any.
     */
    private boolean needsApproval(Sheet sheet, String user, Person person, Claim claim) {
        BigDecimal limit = sheet.getClerkLimit();
        if (limit == null || settings.roleOf(sheet.getId(), user) == Role.STEWARD) {
            return false;
        }
        BigDecimal due = figure(sheet.getId(), person, claim);
        return due != null && due.compareTo(limit) > 0;
    }

    /**
     * The number a claim of this amount corrects: none for one of 0.00 or more; a correction (a
     * negative amount) must name a settled claim of the same person, else it is refused.
     */
    private String correctedNumber(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) return null;
        boolean settled = corrects != null && claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .anyMatch(c -> c.getPersonId() == personId && corrects.equals(c.getNumber())
                        && c.getStatus() == ClaimStatus.SETTLED);
        if (!settled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A correction must name a settled claim of the same person");
        }
        return corrects;
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
