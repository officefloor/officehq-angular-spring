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
 * status (423). No claim dated after its person's retirement date can be added or edited (422).
 * A claim with a negative amount is a correction: it must name, by number, a SETTLED claim of the
 * same person, or it is refused (422); a claim that is not a correction names none.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final PeriodRepository periods;
    private final RateService rates;
    private final RoleService roles;
    private final SheetService sheets;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, PeriodRepository periods, RateService rates,
            RoleService roles, SheetService sheets, Audit audit) {
        this.roles = roles;
        this.claims = claims;
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
            Long corrects) {
        sheets.load(sheetId, user);
        // Lock the sheet, so a period closed at the same moment is seen.
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseIfClosed(sheetId, on);
        refuseIfRetired(person, on);
        Long corrected = correctedNumber(sheetId, personId, amount, corrects);
        Claim claim = claims.save(new Claim(sheetId, personId, scaled(amount), on, note != null ? note : "", corrected));
        record("CLAIM_ADDED", user, person, claim);
        return claim;
    }

    @Transactional
    public Claim update(String sheetId, String user, Long claimId, BigDecimal amount, LocalDate date, String note,
            Long corrects) {
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
        Long corrected = correctedNumber(sheetId, person.getId(), amount, corrects);
        claim.setAmount(scaled(amount));
        claim.setCorrects(corrected);
        claim.setDate(on);
        claim.setNote(note != null ? note : "");
        record("CLAIM_EDITED", user, person, claim);
        return claim;
    }

    /**
     * Move a claim to the next status; a change its status does not allow is refused (409). Opening
     * gives the claim the sheet's next number; a voided claim keeps it (the number is never given again).
     * Opening records who opened it; that user cannot settle it (403).
     * Settling fixes the claim's due at its person's grade's rate in force on the claim's date, raised to
     * the sheet's floor, with its late charge added if it is overdue; later floor and late percent changes
     * leave it as fixed. Every rounding is half up for a claim dated before the sheet's policy date and
     * half to even otherwise; later policy date changes leave it as fixed too. A correction's due is its amount times that rate, rounded the same way, with no
     * floor and no late charge. A clerk
     * can settle a claim only when that due is at or below the sheet's clerk limit, if it has one (403);
     * a claim with no due (no rate) is then refused too, as it cannot be shown to be within the limit.
     * A steward settles any claim.
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
        Long number = next == ClaimStatus.OPEN ? Long.valueOf(sheet.takeClaimNumber()) : claim.getNumber();
        claim.setStatus(next);
        claim.setNumber(number);
        if (next == ClaimStatus.OPEN) {
            claim.setOpenedBy(user);
        }
        List<String> extra = new ArrayList<>();
        if (next == ClaimStatus.SETTLED) {
            BigDecimal due = sheet.liveDue(rates.inForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate())
                    .map(rate -> sheet.dueAt(claim.getAmount(), rate.getRate(), claim.getDate()))
                    .orElse(null), claim.getDate(), claim.isCorrection());
            refuseIfOverClerkLimit(sheet, user, due);
            claim.setSettledDue(due);
            extra.add("due=" + (due == null ? "n/a" : due.toPlainString()));
        }
        record(switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        }, user, person, claim, number, extra);
        return claim;
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
    private Long correctedNumber(String sheetId, Long personId, BigDecimal amount, Long corrects) {
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

    /** Refuse (403) a clerk settling a claim whose due is above the sheet's clerk limit. */
    private void refuseIfOverClerkLimit(Sheet sheet, String user, BigDecimal due) {
        BigDecimal limit = sheet.getClerkLimit();
        if (limit == null || roles.of(sheet.getId(), user) != Role.CLERK) {
            return;
        }
        if (due == null || due.compareTo(limit) > 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A clerk cannot settle a claim with a due above " + limit.toPlainString());
        }
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
    private void record(String event, String user, Person person, Claim claim, Long number, List<String> extra) {
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
