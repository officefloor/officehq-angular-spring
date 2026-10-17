package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The claims kept on a sheet, newest first. A claim without a date takes the sheet's today; a
 * missing note is stored empty. Only a DRAFT claim can be edited (409); an open claim is changed by
 * voiding it and adding a new one. No claim dated in a closed period can be added, edited or change
 * status (423). A claim's due-by is fixed when it is added: its date plus the terms of its person's
 * grade in force on that date (the sheet's terms for a grade without its own); later changes to the
 * terms or the claim's date leave it as fixed. No claim dated after its person's retirement date can be added or edited (422).
 * A claim with a negative amount is a correction: it must name, by number, a SETTLED claim of the
 * same person, or it is refused (422); a claim that is not a correction names none.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final ClaimSequenceRepository sequences;
    private final PersonRepository people;
    private final PeriodRepository periods;
    private final RateService rates;
    private final RoleService roles;
    private final SheetService sheets;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, ClaimSequenceRepository sequences, PersonRepository people, PeriodRepository periods, RateService rates,
            RoleService roles, SheetService sheets, Audit audit) {
        this.roles = roles;
        this.claims = claims;
        this.sequences = sequences;
        this.people = people;
        this.periods = periods;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        sheets.load(sheetId, user);
        // Lock the sheet, so a period closed at the same moment is seen.
        Sheet sheet = sheets.lock(sheetId).orElseThrow();
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseIfClosed(sheetId, on);
        refuseIfRetired(person, on);
        String corrected = correctedNumber(sheetId, personId, amount, corrects);
        Claim claim = claims.save(new Claim(sheetId, personId, scaled(amount), on, note != null ? note : "", corrected,
                sheet.dueBy(person.gradeOn(on), on)));
        record("CLAIM_ADDED", user, person, claim);
        return claim;
    }

    @Transactional
    public Claim update(String sheetId, String user, Long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        sheets.lock(sheetId);
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + claim.getStatus() + " claim cannot be edited");
        }
        refuseIfClosed(sheetId, claim.getDate());
        refuseIfClosed(sheetId, on);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        refuseIfRetired(person, on);
        String corrected = correctedNumber(sheetId, person.getId(), amount, corrects);
        claim.setAmount(scaled(amount));
        claim.setCorrects(corrected);
        claim.setDate(on);
        claim.setNote(note != null ? note : "");
        record("CLAIM_EDITED", user, person, claim);
        return claim;
    }

    /**
     * Move a claim to the next status; a change its status does not allow is refused (409). Opening
     * gives the claim the sheet's next number, or its number within its period while the sheet numbers
     * by period (see {@link #takeNumber}); a voided claim keeps it (the number is never given again).
     * Opening records who opened it; that user cannot settle it (403).
     * Settling fixes the claim's due at its person's grade's rate in force on the claim's date, raised to
     * the sheet's floor, with its late charge added if it is overdue; later floor and late percent changes
     * leave it as fixed. Every rounding is half up for a claim dated before the sheet's policy date and
     * half to even otherwise; later policy date changes leave it as fixed too. A correction's due is its amount times that rate, rounded the same way, with no
     * floor and no late charge. A clerk
     * settling a claim whose due is above the sheet's clerk limit, if it has one, makes it PENDING instead
     * (CLAIM_PENDING), its due left live until a steward approves it; a claim with no due (no rate) is
     * refused (403), as it cannot be shown to be within the limit. A steward settles any claim.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long claimId, ClaimStatus next) {
        // Lock the sheet before reading the claim, so claims opened at the same moment get different numbers
        // and a period closed at the same moment is seen.
        Sheet sheet = sheets.lock(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot settle it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        String number = next == ClaimStatus.OPEN ? takeNumber(sheet, claim.getDate()) : claim.getNumber();
        List<String> extra = new ArrayList<>();
        BigDecimal due = null;
        if (next == ClaimStatus.SETTLED) {
            due = dueNow(sheet, person, claim);
            if (overClerkLimit(sheet, user, due)) {
                next = ClaimStatus.PENDING;
            } else {
                extra.add("due=" + (due == null ? "n/a" : due.toPlainString()));
            }
        }
        claim.setStatus(next);
        claim.setNumber(number);
        if (next == ClaimStatus.OPEN) {
            claim.setOpenedBy(user);
        }
        if (next == ClaimStatus.SETTLED) {
            claim.setSettledDue(due);
        }
        record(switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        }, user, person, claim, number, extra);
        return claim;
    }

    /**
     * A steward approves a PENDING claim: it becomes SETTLED, its due fixed at that moment as settling
     * fixes it (CLAIM_APPROVED due=&lt;due&gt;). Only a PENDING claim can be approved (409), only by a
     * steward, and not by the user who opened it (403).
     */
    @Transactional
    public Claim approve(String sheetId, String user, Long claimId) {
        Sheet sheet = sheets.lock(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        Claim claim = pending(sheetId, user, claimId);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot approve it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        BigDecimal due = dueNow(sheet, person, claim);
        claim.setStatus(ClaimStatus.SETTLED);
        claim.setSettledDue(due);
        record("CLAIM_APPROVED", user, person, claim, claim.getNumber(),
                List.of("due=" + (due == null ? "n/a" : due.toPlainString())));
        return claim;
    }

    /**
     * A steward rejects a PENDING claim with a reason: it goes back to OPEN (CLAIM_REJECTED
     * reason=&lt;reason&gt;). Only a PENDING claim can be rejected (409), only by a steward (403).
     */
    @Transactional
    public Claim reject(String sheetId, String user, Long claimId, String reason) {
        sheets.lock(sheetId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        Claim claim = pending(sheetId, user, claimId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        claim.setStatus(ClaimStatus.OPEN);
        record("CLAIM_REJECTED", user, person, claim, claim.getNumber(), List.of("reason=" + reason));
        return claim;
    }

    /**
     * A steward moves a DRAFT, OPEN or PENDING claim to another person on the sheet: it keeps its number
     * and date, and its due follows its new person's grade (CLAIM_REASSIGNED, naming the new person, with
     * from=&lt;old name&gt; to=&lt;new name&gt;). Only a steward can move a claim (403); a SETTLED or VOID claim
     * cannot move, nor can a claim move to the person it is already on (409); a correction cannot move, as
     * it corrects a claim of its own person, nor can a claim move to a person retired before its date (422).
     */
    @Transactional
    public Claim reassign(String sheetId, String user, Long claimId, Long toPersonId) {
        sheets.lock(sheetId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        if (roles.of(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can reassign a claim");
        }
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + claim.getStatus() + " claim cannot be reassigned");
        }
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Person to = people.findByIdAndSheetId(toPersonId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (to.getId().equals(from.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already on " + to.getName());
        }
        if (claim.isCorrection()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A correction corrects a claim of its own person, so it cannot be reassigned");
        }
        refuseIfRetired(to, claim.getDate());
        claim.setPersonId(to.getId());
        record("CLAIM_REASSIGNED", user, to, claim, claim.getNumber(),
                List.of("from=" + from.getName(), "to=" + to.getName()));
        return claim;
    }

    /** The PENDING claim a steward approves or rejects; refused otherwise (409, 403, 423). */
    private Claim pending(String sheetId, String user, Long claimId) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + claim.getStatus() + " claim is not pending");
        }
        if (roles.of(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can approve or reject a claim");
        }
        return claim;
    }

    /** The claim's due if it were settled now; null when its grade has no rate on its date. */
    private BigDecimal dueNow(Sheet sheet, Person person, Claim claim) {
        return sheet.liveDue(rates.inForce(sheet.getId(), person.gradeOn(claim.getDate()), claim.getDate())
                .map(rate -> sheet.dueAt(claim.getAmount(), rate.getRate(), claim.getDate()))
                .orElse(null), claim.getDate(), claim.getDueBy(), claim.isCorrection());
    }

    /** How many of the selected claims a bulk open opened, and how many it refused. */
    public record BulkResult(int opened, int refused) {
    }

    /**
     * Open the selected claims together, numbering them in list order: the people in the order they
     * were added, and within a person the newest claim (by date) first, claims of the same date in
     * the order they were added. A selected claim that cannot be opened (or is not on the sheet) is
     * refused and left as it is; the others are still opened.
     */
    @Transactional
    public BulkResult bulkOpen(String sheetId, String user, List<Long> claimIds) {
        sheets.lock(sheetId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet"));
        Set<Long> selected = new HashSet<>(claimIds);
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) {
            personOrder.put(listed.get(i).getId(), i);
        }
        List<Claim> ordered = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(claim -> selected.contains(claim.getId()))
                .sorted(Comparator.<Claim>comparingInt(claim -> personOrder.getOrDefault(claim.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getDate, Comparator.reverseOrder())
                        .thenComparing(Claim::getId))
                .toList();
        int opened = 0;
        for (Claim claim : ordered) {
            try {
                changeStatus(sheetId, user, claim.getId(), ClaimStatus.OPEN);
                opened++;
            } catch (ResponseStatusException refused) {
                // Left as it is.
            }
        }
        return new BulkResult(opened, selected.size() - opened);
    }

    /**
     * The number a claim corrects: for a negative amount, the number of a SETTLED claim of the same
     * person, refused (422) when none is named or it names no such claim; none for any other amount.
     */
    private String correctedNumber(String sheetId, Long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            return null;
        }
        boolean settled = corrects != null && claims.findFirstBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(claim -> claim.getStatus() == ClaimStatus.SETTLED)
                .isPresent();
        if (!settled) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A negative amount must correct a settled claim of the same person");
        }
        return corrects;
    }

    /**
     * The number a claim opened now is given: while the sheet numbers by period (its today on or after
     * its period numbering from), the next in the sequence of the period the claim's date is in,
     * &lt;period name&gt;-&lt;n&gt;, or X-&lt;n&gt; for a claim in no period; otherwise the sheet's next number.
     * The caller holds the sheet's lock.
     */
    private String takeNumber(Sheet sheet, LocalDate date) {
        if (!sheet.numbersByPeriod()) {
            return String.valueOf(sheet.takeClaimNumber());
        }
        String prefix = periods.findBySheetIdOrderByFromAsc(sheet.getId()).stream()
                .filter(period -> period.contains(date))
                .map(Period::getName)
                .findFirst()
                .orElse("X");
        ClaimSequence sequence = sequences.findById(new ClaimSequence.Key(sheet.getId(), prefix))
                .orElseGet(() -> sequences.save(new ClaimSequence(sheet.getId(), prefix)));
        return sequence.take();
    }

    /**
     * Whether a clerk settling a claim of this due is above the sheet's clerk limit, so it goes PENDING;
     * refused (403) when the claim has no due, as it cannot be shown to be within the limit.
     */
    private boolean overClerkLimit(Sheet sheet, String user, BigDecimal due) {
        BigDecimal limit = sheet.getClerkLimit();
        if (limit == null || roles.of(sheet.getId(), user) != Role.CLERK) {
            return false;
        }
        if (due == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A clerk cannot settle a claim with no due while there is a clerk limit");
        }
        return due.compareTo(limit) > 0;
    }

    /** Refuse (423) a change to a claim dated in a closed period. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(period -> period.isClosed() && period.contains(date))
                .findFirst()
                .ifPresent(period -> {
                    throw new ResponseStatusException(HttpStatus.LOCKED, "The period " + period.getName() + " is closed");
                });
    }

    /** Refuse (422) a claim dated after its person's retirement date. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (person.retiredOn(date)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    person.getName() + " is retired from " + person.getRetiredFrom());
        }
    }

    private void record(String event, String user, Person person, Claim claim) {
        record(event, user, person, claim, claim.getNumber(), List.of());
    }

    /**
     * Amounts are written with two decimals, as shown, and dates as yyyy-mm-dd. A claim with a number adds number=&lt;number&gt;; any extra fields follow it.
     */
    private void record(String event, String user, Person person, Claim claim, String number, List<String> extra) {
        List<String> fields = new ArrayList<>(List.of("person=" + person.getName(),
                "amount=" + claim.getAmount().toPlainString(), "date=" + claim.getDate()));
        if (number != null) {
            fields.add("number=" + number);
        }
        fields.addAll(extra);
        audit.change(event, claim.getSheetId(), user, fields.toArray(String[]::new));
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
