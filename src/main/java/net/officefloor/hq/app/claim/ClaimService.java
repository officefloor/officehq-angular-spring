package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people on a sheet make: listed, added, edited and moved through their statuses. */
@Service
public class ClaimService {

    /** Shown in place of a due when the grade has no rate. */
    private static final String NO_RATE = "n/a";

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final Dues dues;
    private final SheetService sheets;
    private final Rollups rollups;
    private final PeriodService periods;
    private final RoleService roles;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, Dues dues, SheetService sheets,
            Rollups rollups, PeriodService periods, RoleService roles, Audit audit) {
        this.periods = periods;
        this.roles = roles;
        this.claims = claims;
        this.people = people;
        this.dues = dues;
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
     * Add a claim for a person on the sheet; with no date it is dated the sheet's today. Refused when
     * it is dated inside a closed period, or when it is a correction that does not name a SETTLED claim
     * of the same person (see {@link #refuseUnlessCorrects}).
     */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Rollups.Rollup before = rollups.take(sheetId);
        Person person = person(sheetId, personId);
        LocalDate dated = date != null ? date : sheets.today(sheetId);
        LocalDate dueBy = dated.plusDays(sheets.lock(sheetId).termsFor(person.gradeOn(dated)));
        periods.refuseIfClosed(sheetId, dated);
        refuseIfRetired(person, dated);
        refuseUnlessCorrects(sheetId, personId, amount, corrects);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, money(amount), dated, dueBy, clean(note), corrects));
        audited("CLAIM_ADDED", sheetId, user, person, claim);
        rollups.audit(sheetId, user, before);
        return claim;
    }

    /**
     * Change a claim's amount, date, note and the number it corrects; with no date it keeps its own. Only a DRAFT claim can be
     * edited: an OPEN one is changed by voiding it and adding a new one. Refused when the claim, or the
     * date it moves to, is inside a closed period, or when it would be a correction that does not name a
     * SETTLED claim of the same person.
     */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Rollups.Rollup before = rollups.take(sheetId);
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; only a DRAFT can.");
        }
        periods.refuseIfClosed(sheetId, claim.getDate());
        if (date != null) {
            periods.refuseIfClosed(sheetId, date);
            refuseIfRetired(person(sheetId, claim.getPersonId()), date);
        }
        refuseUnlessCorrects(sheetId, claim.getPersonId(), amount, corrects);
        claim.change(money(amount), date != null ? date : claim.getDate(), clean(note), corrects);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Move a claim to a new status, recording the event; refused (and nothing written) when the
     * claim's current status does not allow the move. Settling fixes the claim's due at the rate
     * the person's grade has in force on the claim's date, or at the figure a statement bound it to,
     * with the late charge it has then frozen in.
     * Opening numbers the claim and records who opened it; a voided claim keeps its number, which
     * is never reused. The user who opened a claim
     * is refused when settling it. A clerk settling a claim whose due is above the sheet's clerk limit
     * (a due of n/a is within any limit) sends it PENDING instead, for a steward to approve or reject;
     * a steward settles any. A claim dated inside a closed period cannot be moved.
     */
    @Transactional
    public Claim move(String sheetId, String user, long claimId, ClaimStatus next) {
        // Every move takes the sheet's lock before reading the claim, so concurrent opens on the
        // sheet are serialised: each sees the other's committed claim and number.
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        periods.refuseIfClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The user who opened a claim cannot settle it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal settledDue = next == ClaimStatus.SETTLED
                ? dues.settling(claim, person.gradeOn(claim.getDate()))
                : null;
        if (next == ClaimStatus.SETTLED && overClerkLimit(sheetId, user, settledDue)) {
            next = ClaimStatus.PENDING;
            settledDue = null;
        }
        claim.become(next);
        if (next == ClaimStatus.OPEN) {
            claim.number(periods.nextClaimNumber(sheetId, claim.getDate()));
            claim.openedBy(user);
        }
        if (next == ClaimStatus.SETTLED) {
            claim.settleDue(settledDue);
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case PENDING -> "CLAIM_PENDING";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        String suffix = next == ClaimStatus.SETTLED
                ? " due=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE)
                : "";
        audited(event, sheetId, user, person, saved, saved.getNumber(), suffix);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Approve a PENDING claim: it becomes SETTLED, its due fixed at this moment at its live figure with
     * the late charge it has then. Only a steward approves, and not the user who opened the claim.
     */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = pending(sheetId, user, claimId);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The user who opened a claim cannot approve it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        claim.settleDue(dues.approving(claim, person.gradeOn(claim.getDate())));
        claim.become(ClaimStatus.SETTLED);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_APPROVED", sheetId, user, person, saved, saved.getNumber(),
                " due=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE));
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Reject a PENDING claim with a reason: it is OPEN again, bound again by its person's last
     * statement when that statement included it. Only a steward rejects.
     */
    @Transactional
    public Claim reject(String sheetId, String user, long claimId, String reason) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = pending(sheetId, user, claimId);
        claim.become(ClaimStatus.OPEN);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_REJECTED", sheetId, user, person(sheetId, saved.getPersonId()), saved, saved.getNumber(),
                " reason=" + reason.trim());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Move a DRAFT, OPEN or PENDING claim to another person on the sheet: it keeps its number and
     * date, and loses any figure a statement bound it to, its due live again until its new person's
     * next statement. Only a steward reassigns. Refused when the claim is SETTLED or VOID, when it is
     * dated inside a closed period, when the new person is its own, is retired before its date, or (for
     * a correction) has no SETTLED claim of the number it corrects.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long personId) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward reassigns a claim.");
        }
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reassigned.");
        }
        periods.refuseIfClosed(sheetId, claim.getDate());
        Person from = person(sheetId, claim.getPersonId());
        Person to = person(sheetId, personId);
        if (from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already " + to.getName() + "'s.");
        }
        refuseIfRetired(to, claim.getDate());
        refuseUnlessCorrects(sheetId, personId, claim.getAmount(), claim.getCorrects());
        claim.reassign(to.getId());
        claim.unbind();
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_REASSIGNED", sheetId, user, to, saved, saved.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * The PENDING claim a steward approves or rejects; refused when it is not PENDING, when its date
     * is inside a closed period, or when the user is not a steward.
     */
    private Claim pending(String sheetId, String user, long claimId) {
        Claim claim = claim(sheetId, claimId);
        periods.refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not pending approval.");
        }
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward approves or rejects a claim.");
        }
        return claim;
    }

    /** Whether a clerk is settling a claim whose due is above the sheet's clerk limit. */
    private boolean overClerkLimit(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = sheets.clerkLimit(sheetId);
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return false;
        }
        return Role.CLERK.equals(roles.roleOf(sheetId, user));
    }

    /**
     * Refuse a correction (an amount below 0.00) unless it names, by number, a SETTLED claim of the
     * same person; and refuse naming a claim to correct on any other claim.
     */
    private void refuseUnlessCorrects(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only a correction, with an amount below 0.00, names a claim it corrects.");
            }
            return;
        }
        boolean settled = corrects != null && claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(corrected -> corrected.getStatus() == ClaimStatus.SETTLED)
                .isPresent();
        if (!settled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A correction must name the number of a SETTLED claim of the same person.");
        }
    }

    /** Refuse a claim dated after its person's retirement. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (!person.canClaimOn(date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is retired from " + person.getRetiredFrom() + ": a claim cannot be dated after it.");
        }
    }

    private Claim claim(String sheetId, long claimId) {
        return claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
    }

    /** Record the change to the claim once it is committed; a claim with a number carries it. */
    private void audited(String event, String sheetId, String user, Person person, Claim claim) {
        audited(event, sheetId, user, person, claim, claim.getNumber(), "");
    }

    private void audited(String event, String sheetId, String user, Person person, Claim claim, String number,
            String suffix) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "") + suffix);
    }

    /** The due figure a claim shows, without its late charge (see {@link Dues#figure}). */
    public BigDecimal figure(Claim claim, String grade) {
        return dues.figure(claim, grade);
    }

    /** The due a claim shows, with its late charge (see {@link Dues#due}). */
    public BigDecimal due(Claim claim, String grade) {
        return dues.due(claim, grade);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String clean(String note) {
        return note == null ? "" : note.trim();
    }
}
