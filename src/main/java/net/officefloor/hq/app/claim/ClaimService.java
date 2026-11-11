package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
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
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the people's claims on a sheet. A claim with no date is dated the sheet's today. A claim dated in a closed
 * period cannot be added, edited or change status. Only a DRAFT claim can be edited. No claim
 * can be dated after the retirement date of its person.
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

    public ClaimService(ClaimRepository claims, PersonRepository people, RateService rates, SheetService sheets,
            Audit audit, RollupService rollup, PeriodService periods, RoleService roles, SettingService settings) {
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
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        LocalDate dated = dateOr(sheetId, date);
        periods.requireOpen(sheetId, dated);
        person.requireClaimable(dated);
        Claim saved = claims.saveAndFlush(new Claim(sheetId, personId, amount, dated, noteOf(note)));
        audit.recordOnCommit(record("CLAIM_ADDED", user, person, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    public Claim update(String sheetId, String user, Long id, BigDecimal amount, LocalDate date, String note) {
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
        claim.setAmount(amount);
        claim.setDate(dated);
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, saved.getPersonId()));
        audit.recordOnCommit(record("CLAIM_EDITED", user, person, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Move a claim to another status, if its current one allows it; refused otherwise. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long id, ClaimStatus status) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (!claim.getStatus().canMoveTo(status)) {
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
        // is at or below the clerk limit; a due of n/a is within any limit.
        if (status == ClaimStatus.SETTLED && roles.roleOf(sheetId, user) == Role.CLERK) {
            BigDecimal limit = settings.get(sheetId).getClerkLimit();
            BigDecimal due = withLateCharge(sheetId, claim,
                    claim.isStated() ? claim.getStatedDue() : dueOf(sheetId, person, claim));
            if (limit != null && due != null && due.compareTo(limit) > 0) {
                throw new ClaimOverClerkLimitException(id, due, limit);
            }
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(status);
        // Settling fixes the due it shows: its figure (the one its statement bound it to, else worked out from the
        // rate on the claim's date) with its late charge frozen in; with no figure it stays live.
        String due = "";
        if (status == ClaimStatus.SETTLED) {
            BigDecimal figure = claim.isStated() ? claim.getStatedDue() : dueOf(sheetId, person, claim);
            BigDecimal settledDue = figure == null ? null
                    : figure.add(settings.get(sheetId).lateCharge(figure, claim.getDate()));
            claim.setSettledDue(settledDue);
            due = " due=" + (settledDue == null ? "n/a" : settledDue.toPlainString());
        }
        // A voided claim keeps its number (never reused), and the CLAIM_VOIDED record carries it.
        Long number = claim.getNumber();
        if (status == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
            claim.setNumber(number);
            claim.setOpenedBy(user);
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (status) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, user, person, saved) + numberOf(number) + due);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * The claim's amount at the rate its person's grade has on its date, two decimals, raised to the sheet's floor;
     * null (n/a) with no rate.
     */
    private BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        return settings.get(sheetId).floored(rates.rateOn(sheetId, person.getGrade(), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN))
                .orElse(null));
    }

    /** The due with the late charge of an OPEN claim overdue on the sheet's today added on top; null (n/a) stays null. */
    private BigDecimal withLateCharge(String sheetId, Claim claim, BigDecimal due) {
        return settings.get(sheetId).withLateCharge(due, claim.getStatus() == ClaimStatus.OPEN, claim.getDate());
    }

    /** The amount as shown (two decimals) and the date as yyyy-mm-dd. */
    private static String record(String event, String user, Person person, Claim claim) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + " date=" + claim.getDate();
    }

    /** The claim's number at the end of an audit record, or nothing when it has none. */
    private static String numberOf(Long number) {
        return number == null ? "" : " number=" + number;
    }

    private LocalDate dateOr(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
