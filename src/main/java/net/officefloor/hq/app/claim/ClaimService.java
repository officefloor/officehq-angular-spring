package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people make on a sheet. */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final Dues dues;
    private final SheetService sheets;
    private final Rollup rollup;
    private final PeriodRepository periods;
    private final RoleService roles;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, Dues dues, SheetService sheets,
            Rollup rollup, PeriodRepository periods, RoleService roles, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.dues = dues;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
        this.roles = roles;
        this.audit = audit;
    }

    /** The claims on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a date left out takes the sheet's today. Refused when the date falls
     *  inside a closed period, or after the person's retirement day, and when a correction (a negative
     *  amount) does not name a settled claim of the person. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        return add(sheetId, user, personId, amount, date, note, null);
    }

    /** Add a claim for a person, naming the number of the claim it corrects (only a correction keeps it). */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, on);
        refuseRetired(person(sheetId, personId), on);
        Claim corrected = corrected(sheetId, personId, amount, corrects);
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, personId);
            Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, on, note,
                    corrected == null ? null : corrected.getNumber(), corrected == null ? null : corrected.getDate()));
            audit.recordOnCommit(record("CLAIM_ADDED", sheetId, user, person, claim));
            return claim;
        });
    }

    /** Change a draft claim's amount, date and note; a date left out takes the sheet's today. Refused
     *  when the claim is not a draft (an open claim is changed by voiding it and adding a new one), and
     *  when the claim's date, or the new one, falls inside a closed period, and when the new one is
     *  after the person's retirement day. */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited");
        }
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDate());
        refuseClosed(sheetId, on);
        refuseRetired(person(sheetId, claim.getPersonId()), on);
        Claim corrected = corrected(sheetId, claim.getPersonId(), amount, corrects);
        return rollup.tracking(sheetId, user, () -> {
            claim.setAmount(amount);
            claim.setCorrects(corrected == null ? null : corrected.getNumber());
            claim.setCorrectedDate(corrected == null ? null : corrected.getDate());
            claim.setDate(on);
            claim.setNote(note);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved));
            return saved;
        });
    }

    /** Move a claim to a new status; a change its current status does not allow is refused, and so
     *  is settling a claim by the user who opened it, settling by a clerk a claim whose due is above
     *  the sheet's clerk limit, and any change to a claim dated inside a closed period. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + to);
        }
        if (to == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it");
        }
        if (to == ClaimStatus.SETTLED) {
            refuseOverClerkLimit(sheetId, user, claim);
        }
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            if (to == ClaimStatus.SETTLED) {
                // Settling fixes the due at what it is now, while the claim is still open (the figure a
                // statement bound it to, if any, and its late charge); later changes to rates, the
                // late percent and the sheet's today do not alter it.
                claim.setSettledDue(dues.figure(sheetId, person, claim));
            }
            // A void claim keeps the number it had (never reused).
            claim.setStatus(to);
            if (to == ClaimStatus.OPEN) {
                claim.setNumber(nextNumber(sheetId, claim.getDate()));
                claim.setOpenedBy(user);
            }
            Claim saved = claims.saveAndFlush(claim);
            String event = switch (to) {
                case OPEN -> "CLAIM_OPENED";
                case SETTLED -> "CLAIM_SETTLED";
                case VOID -> "CLAIM_VOIDED";
                case DRAFT -> throw new IllegalStateException("a claim never returns to DRAFT");
            };
            String line = record(event, sheetId, user, person, saved);
            if (to == ClaimStatus.SETTLED) {
                line += " due=" + (saved.getSettledDue() == null ? "n/a" : saved.getSettledDue().setScale(2).toPlainString());
            }
            audit.recordOnCommit(line);
            return saved;
        });
    }

    /** The number for a claim of the date opened now: under period numbering, the next in its period
     *  (<period name>-<n>), or for one in no period the next X-<n>; otherwise the next on the sheet. */
    private String nextNumber(String sheetId, LocalDate date) {
        if (!sheets.periodNumbering(sheetId)) {
            return String.valueOf(sheets.nextClaimNumber(sheetId));
        }
        return periods.findOn(sheetId, date)
                .map(p -> {
                    periods.advanceClaimNumber(p.getId());
                    return p.getName() + "-" + periods.lastClaimNumber(p.getId());
                })
                .orElseGet(() -> "X-" + sheets.nextUnperiodNumber(sheetId));
    }

    /** The claim a claim of the amount corrects: none for an amount of 0.00 or more; for a correction
     *  (a negative amount), the one of the number given, which must be a settled claim of the person,
     *  or else it is refused. */
    private Claim corrected(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            return null;
        }
        return (corrects == null ? Optional.<Claim>empty()
                : claims.findBySheetIdAndPersonIdAndNumberAndStatus(sheetId, personId, corrects, ClaimStatus.SETTLED))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A negative amount must correct a settled claim of the same person"));
    }

    /** Refuse a change to a claim dated on the day when the day falls inside a closed period. */
    private void refuseClosed(String sheetId, LocalDate day) {
        if (periods.closedOn(sheetId, day)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "That claim's period is closed");
        }
    }

    /** Refuse a clerk settling a claim whose due is above the sheet's clerk limit; a claim with no
     *  due (n/a) is within any limit, and a steward settles anything. */
    private void refuseOverClerkLimit(String sheetId, String user, Claim claim) {
        BigDecimal limit = sheets.clerkLimit(sheetId);
        if (limit == null || SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            return;
        }
        BigDecimal due = dues.figure(sheetId, person(sheetId, claim.getPersonId()), claim);
        if (due != null && due.compareTo(limit) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A clerk cannot settle a claim whose due is above the clerk limit");
        }
    }

    /** Refuse a claim of the person dated after the day they are retired from. */
    private static void refuseRetired(Person person, LocalDate day) {
        if (person.getRetiredFrom() != null && day.isAfter(person.getRetiredFrom())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim cannot be dated after the person's retirement");
        }
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
    }

    /** An audit record about a claim; one that has a number carries it at the end. */
    private static String record(String event, String sheetId, String user, Person person, Claim claim) {
        String number = claim.getNumber();
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "");
    }
}
