package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the people's claims on a sheet. A claim with no date is dated the sheet's today. A claim dated in a closed
 * period cannot be added, edited or change status. Only a DRAFT claim can be edited; a PENDING claim, waiting for a
 * steward to approve or reject it, is otherwise handled like an OPEN one. No claim
 * can be dated after the retirement date of its person. A claim with a negative amount is a correction: it must name,
 * by number, a SETTLED claim of the same person that it corrects, and its due has no floor and no late charge.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final PeriodService periods;
    private final RoleService roles;
    private final SettingService settings;
    private final ClaimLogRepository logs;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateService rates, SheetService sheets,
            Audit audit, RollupService rollup, PeriodService periods, RoleService roles, SettingService settings,
            ClaimLogRepository logs) {
        this.logs = logs;
        this.periods = periods;
        this.roles = roles;
        this.settings = settings;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
    }

    /** The status logs of the sheet's claims by claim id, each oldest first. */
    public Map<Long, List<ClaimLog>> logs(String sheetId) {
        return logs.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(ClaimLog::getClaimId, Collectors.toList()));
    }

    /** The claim's status log, oldest first. */
    public List<ClaimLog> logOf(Claim claim) {
        return logs.findByClaimIdOrderByIdAsc(claim.getId());
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        LocalDate dated = dateOr(sheetId, date);
        periods.requireOpen(sheetId, dated);
        person.requireClaimable(dated);
        LocalDate correctedDate = requireCorrection(sheetId, personId, amount, corrects);
        Claim claim = new Claim(sheetId, personId, amount, dated, noteOf(note));
        claim.setCorrects(corrects);
        claim.setRoundingDate(correctedDate != null ? correctedDate : dated);
        // Due by its date plus the terms of its person's grade on that date, fixed from now on.
        claim.setDueBy(dated.plusDays(settings.termsFor(sheetId, person.gradeOn(dated))));
        claim.setListed(sheets.nextListed(sheetId));
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_ADDED", user, person, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    public Claim update(String sheetId, String user, Long id, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        // Only a DRAFT can be edited: an OPEN claim is changed by voiding it and adding a new one.
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(id, claim.getStatus());
        }
        LocalDate dated = dateOr(sheetId, date);
        // Neither out of nor into a closed period.
        periods.requireOpen(sheetId, claim.getDate(), dated);
        people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()))
                .requireClaimable(dated);
        LocalDate correctedDate = requireCorrection(sheetId, claim.getPersonId(), amount, corrects);
        claim.setAmount(amount);
        claim.setCorrects(corrects);
        claim.setDate(dated);
        claim.setRoundingDate(correctedDate != null ? correctedDate : dated);
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, saved.getPersonId()));
        audit.recordOnCommit(record("CLAIM_EDITED", user, person, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Move a claim to another status, if its current one allows it; refused otherwise. A clerk settling a claim whose
     * due is above the clerk limit sends it to PENDING instead, for a steward to approve or reject.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long id, ClaimStatus status) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (!claim.getStatus().canMoveTo(status) || status == ClaimStatus.PENDING) {
            throw new ClaimStatusException(id, claim.getStatus(), status);
        }
        periods.requireOpen(sheetId, claim.getDate());
        // Four eyes: whoever opened a claim cannot also settle it.
        if (status == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ClaimSettledByOpenerException(id, user);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        // A clerk settles only a claim whose due (the one its statement bound it to, else worked out from the rate)
        // is at or below the clerk limit; one above it goes to PENDING. A due of n/a is within any limit.
        if (status == ClaimStatus.SETTLED && roles.roleOf(sheetId, user) == Role.CLERK) {
            BigDecimal limit = settings.get(sheetId).getClerkLimit();
            BigDecimal due = withLateCharge(sheetId, claim, figureOf(sheetId, person, claim));
            if (limit != null && due != null && due.compareTo(limit) > 0) {
                status = ClaimStatus.PENDING;
            }
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String due = status == ClaimStatus.SETTLED ? settle(sheetId, person, claim) : "";
        claim.setStatus(status);
        // A voided claim keeps its number (never reused), and the CLAIM_VOIDED record carries it.
        String number = claim.getNumber();
        if (status == ClaimStatus.OPEN) {
            number = nextNumber(sheetId, claim.getDate());
            claim.setNumber(number);
            claim.setOpenedBy(user);
        }
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        String event = switch (status) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, user, person, saved) + numberOf(number) + due);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward other than its opener approves a PENDING claim: it becomes SETTLED, its due frozen at that moment from
     * its live figure.
     */
    @Transactional
    public Claim approve(String sheetId, String user, Long id) {
        Claim claim = pendingForDecision(sheetId, user, id, ClaimStatus.SETTLED);
        if (user.equals(claim.getOpenedBy())) {
            throw new ClaimApprovalForbiddenException(id, user, "whoever opened a claim cannot approve it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String due = settle(sheetId, person, claim);
        claim.setStatus(ClaimStatus.SETTLED);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_APPROVED", user, person, saved) + numberOf(saved.getNumber()) + due);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward rejects a PENDING claim with a reason: it goes back to OPEN, bound again by its person's last statement
     * if that statement included it.
     */
    @Transactional
    public Claim reject(String sheetId, String user, Long id, String reason) {
        Claim claim = pendingForDecision(sheetId, user, id, ClaimStatus.OPEN);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_REJECTED", user, person, saved) + numberOf(saved.getNumber())
                + " reason=" + reason);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward moves a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its number and date,
     * loses any figure a statement bound it to (live again until its new person's next statement), and is listed as
     * its new person's newest claim. Refused for a SETTLED or VOID claim, its own person, a closed period, a new person
     * retired before its date, or a user not a steward.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, Long id, Long toPersonId) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimReassignForbiddenException(id, user);
        }
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ClaimReassignException(id, "it is " + claim.getStatus());
        }
        if (claim.getPersonId().equals(toPersonId)) {
            throw new ClaimReassignException(id, "it is already that person's");
        }
        periods.requireOpen(sheetId, claim.getDate());
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        Person to = people.findByIdAndSheetId(toPersonId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, toPersonId));
        to.requireClaimable(claim.getDate());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setPersonId(to.getId());
        claim.setStated(false, null);
        claim.setListed(sheets.nextListed(sheetId));
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_REASSIGNED", user, to, saved) + numberOf(saved.getNumber())
                + " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward re-settles a SETTLED claim: its due is frozen again at the figure it would have today as an OPEN claim
     * bound by no statement (today's rates, grades and floor, with its late charge as of today). Refused for any other
     * status, a closed period, or a user not a steward.
     */
    @Transactional
    public Claim resettle(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimResettleForbiddenException(id, user);
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ClaimStatusException(id, claim.getStatus(), ClaimStatus.SETTLED);
        }
        periods.requireOpen(sheetId, claim.getDate());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String old = shownDue(sheetId, person, claim);
        BigDecimal figure = dueOf(sheetId, person, claim);
        claim.setSettledDue(settings.get(sheetId).withLateCharge(figure, !claim.isCorrection(), claim.getDueBy(),
                claim.getDate()));
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_RESETTLED", user, person, saved) + numberOf(saved.getNumber())
                + " old=" + old + " new=" + shownDue(sheetId, person, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** The PENDING claim a steward decides; refused for any other status, a closed period, or a user not a steward. */
    private Claim pendingForDecision(String sheetId, String user, Long id, ClaimStatus to) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ClaimStatusException(id, claim.getStatus(), to);
        }
        periods.requireOpen(sheetId, claim.getDate());
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimApprovalForbiddenException(id, user, "only a steward approves or rejects a pending claim");
        }
        return claim;
    }

    /**
     * Fixes the due the claim shows as it settles: its figure (the one its statement binds it to, else worked out from
     * the rate on the claim's date) with its late charge frozen in; with no figure it stays live. Gives the due at the
     * end of its audit record.
     */
    private String settle(String sheetId, Person person, Claim claim) {
        BigDecimal figure = figureOf(sheetId, person, claim);
        BigDecimal settledDue = figure == null || claim.isCorrection() ? figure
                : figure.add(settings.get(sheetId).lateCharge(figure, claim.getDueBy(), claim.getDate()));
        claim.setSettledDue(settledDue);
        return " due=" + (settledDue == null ? "n/a" : settledDue.toPlainString());
    }

    /** Logs the claim's status now: who changed it, the sheet's today, and its due as computed at this moment. */
    private void log(String sheetId, String user, Person person, Claim claim) {
        logs.save(new ClaimLog(sheetId, claim.getId(), claim.getStatus(), user, sheets.today(sheetId),
                shownDue(sheetId, person, claim)));
    }

    /**
     * The claim's due as shown: the one fixed when it was settled, else its figure with its late charge on top; two
     * decimals, or n/a.
     */
    private String shownDue(String sheetId, Person person, Claim claim) {
        BigDecimal due = claim.getSettledDue() != null ? claim.getSettledDue()
                : withLateCharge(sheetId, claim, figureOf(sheetId, person, claim));
        return due == null ? "n/a" : due.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** The figure under the claim's due: the one its statement binds it to now, else worked out from the rate. */
    private BigDecimal figureOf(String sheetId, Person person, Claim claim) {
        return claim.isBound() ? claim.getStatedDue() : dueOf(sheetId, person, claim);
    }

    /**
     * A negative amount (a correction) must name, by number, a SETTLED claim of the same person; any other amount
     * names none. Refused otherwise. Gives the date of the claim a correction corrects (it rounds by that date), null
     * for any other claim.
     */
    private LocalDate requireCorrection(String sheetId, Long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ClaimCorrectionException("Only a correction (a negative amount) corrects a claim");
            }
            return null;
        }
        if (corrects == null) {
            throw new ClaimCorrectionException("A negative amount must name the settled claim it corrects");
        }
        return claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(c -> c.getStatus() == ClaimStatus.SETTLED)
                .map(Claim::getDate)
                .orElseThrow(() -> new ClaimCorrectionException(
                        "Claim " + corrects + " is not a settled claim of the same person"));
    }

    /**
     * The claim's amount at the rate its person's grade has on its date, two decimals rounded as the sheet's policy
     * date has the claim round, raised to the sheet's floor unless it is a correction; null (n/a) with no rate.
     */
    private BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        Sheet sheet = settings.get(sheetId);
        BigDecimal due = rates.rateOn(sheetId, person.gradeOn(claim.getDate()), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> sheet.rateStep(claim.getAmount(), pct, claim.getRoundingDate()))
                .orElse(null);
        return claim.isCorrection() ? due : sheet.floored(due);
    }

    /**
     * The due with the late charge of an OPEN or PENDING claim overdue on the sheet's today added on top (none on a
     * correction); null (n/a) stays null.
     */
    private BigDecimal withLateCharge(String sheetId, Claim claim, BigDecimal due) {
        return settings.get(sheetId).withLateCharge(due,
                claim.getStatus().isOpen() && !claim.isCorrection(), claim.getDueBy(), claim.getDate());
    }

    /** The amount as shown (two decimals) and the date as yyyy-mm-dd. */
    private static String record(String event, String user, Person person, Claim claim) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + " date=" + claim.getDate();
    }

    /** The claim's number at the end of an audit record, or nothing when it has none. */
    private static String numberOf(String number) {
        return number == null ? "" : " number=" + number;
    }

    /**
     * The number a claim dated on the date gets when opened: while the sheet numbers by period, the next in its
     * period's own sequence (X for no period); otherwise the next sheet-wide number.
     */
    private String nextNumber(String sheetId, LocalDate date) {
        if (settings.get(sheetId).numbersByPeriod()) {
            return sheets.nextSequenceNumber(sheetId, periods.nameOn(sheetId, date).orElse("X"));
        }
        return String.valueOf(sheets.nextClaimNumber(sheetId));
    }

    private LocalDate dateOr(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
