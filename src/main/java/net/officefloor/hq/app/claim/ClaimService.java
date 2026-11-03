package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The claims on a sheet: added to a person and edited (amount, date, note). A claim given no date
 * takes the sheet's today. A claim starts as DRAFT and moves through its statuses (see
 * {@link ClaimStatus}); a change its status does not allow is refused. Only a DRAFT can be edited:
 * to change an open claim, void it and add a new one. Settling a claim fixes its
 * due at the rate its person's grade has then for the claim's date; an open claim's due stays live.
 * A claim records who opened it, and that user cannot settle it (four eyes). A claim bound by its
 * person's last statement keeps the figure it had then, and settling it fixes that figure. No claim
 * dated inside a closed period can be added, edited (to or from such a date) or change status. No
 * claim can be added or edited to a date after its person's retirement date. A clerk settles a claim
 * only when its figure is at or below the sheet's clerk limit (a claim with no figure is within any
 * limit); a steward settles any claim. A claim's live due is never below the sheet's floor. An
 * overdue claim's due gains a late charge on top of its figure (see {@link LateCharge}); a statement
 * binds only the figure under it, and settling fixes the charge into the settled due.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final PeriodService periods;
    private final RoleService roles;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates, SheetService sheets,
            Audit audit, RollupService rollup, PeriodService periods, RoleService roles) {
        this.roles = roles;
        this.rollup = rollup;
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /**
     * A claim's figure under any late charge: the due fixed when settled, else the one bound by a
     * statement, else the live due; null when it has none.
     */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getStatus() == ClaimStatus.SETTLED) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue() : due(sheetId, person, claim);
    }

    /** A claim's figure with its late charge on the sheet's today added on top; null when it has none. */
    public BigDecimal charged(String sheetId, Person person, Claim claim) {
        return LateCharge.withCharge(sheets.sheet(sheetId), claim, figure(sheetId, person, claim));
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = person(sheetId, personId);
        LocalDate dated = dateOrToday(sheetId, date);
        periods.refuseIfClosed(sheetId, dated);
        refuseIfRetired(person, dated);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim added = claims.saveAndFlush(new Claim(sheetId, personId, amount, dated, note));
        audit.recordOnCommit(record("CLAIM_ADDED", sheetId, user, person, added));
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited.");
        }
        LocalDate dated = dateOrToday(sheetId, date);
        periods.refuseIfClosed(sheetId, claim.getDate());
        periods.refuseIfClosed(sheetId, dated);
        refuseIfRetired(person(sheetId, claim.getPersonId()), dated);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setDate(dated);
        claim.setNote(note);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Move a claim to a new status, refused (409) when its current status does not allow it, and
     * refused (403) when the user who opened it tries to settle it, or a clerk tries to settle one
     * whose figure is above the sheet's clerk limit.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        // lock the sheet's numbering before reading the claim, so a claim is opened (and numbered) once
        Integer number = next == ClaimStatus.OPEN ? sheets.nextClaimNumber(sheetId) : null;
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        periods.refuseIfClosed(sheetId, claim.getDate());
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        // settling fixes the figure with its late charge as it stands on the sheet's today
        BigDecimal settling = next == ClaimStatus.SETTLED ? charged(sheetId, person, claim) : null;
        if (next == ClaimStatus.SETTLED) {
            refuseIfAboveClerkLimit(sheetId, user, settling);
        }
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            claim.setNumber(number);
            claim.setOpenedBy(user);
        } else if (next == ClaimStatus.SETTLED) {
            claim.setSettledDue(settling);
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, sheetId, user, person, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : "")
                + (next == ClaimStatus.SETTLED ? " due=" + dueText(saved.getSettledDue()) : ""));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * The due on a claim's amount at the rate for the person's grade in force on the claim's date,
     * half to even to the cent and raised to the sheet's floor; null with no rate in force then.
     */
    public BigDecimal due(String sheetId, Person person, Claim claim) {
        BigDecimal floor = sheets.floor(sheetId);
        return rates.findInForce(sheetId, person.getGrade(), claim.getDate()).map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                .map(due -> due.max(floor))
                .orElse(null);
    }

    private static String dueText(BigDecimal due) {
        return due == null ? "n/a" : due.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** Refuse (403) a clerk settling a claim whose figure is above the sheet's clerk limit. */
    private void refuseIfAboveClerkLimit(String sheetId, String user, BigDecimal figure) {
        BigDecimal limit = sheets.clerkLimit(sheetId);
        if (figure == null || limit == null || figure.compareTo(limit) <= 0
                || Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "A clerk cannot settle a claim whose due is above the clerk limit of " + dueText(limit) + ".");
    }

    /** Refuse (422) a claim dated after its person's retirement date. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (person.isRetired() && date.isAfter(person.getRetiredFrom())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "That claim's date is after its person's retirement date.");
        }
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
    }

    private static String record(String event, String sheetId, String user, Person person, Claim claim) {
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName() + " amount="
                + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString() + " date=" + claim.getDate();
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }
}
