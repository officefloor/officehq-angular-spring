package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claims on a sheet: listed newest first, added to a person and edited one at a time. A claim
 * given no date takes the sheet's today. A claim is added as DRAFT and moves through its statuses
 * as {@link ClaimStatus} allows; a change it does not allow is refused. Opening a claim gives it
 * the sheet's next number; voiding it clears the number, which is never given again.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, dateOrToday(sheetId, date), note));
        record("CLAIM_ADDED", sheetId, user, person, claim);
        return claim;
    }

    @Transactional
    public Claim edit(String sheetId, String user, Long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        claim.setAmount(amount);
        claim.setDate(dateOrToday(sheetId, date));
        claim.setNote(note);
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        record("CLAIM_EDITED", sheetId, user, person, saved);
        return saved;
    }

    @Transactional
    public Claim changeStatus(String sheetId, String user, Long id, ClaimStatus next) {
        // Opening takes the sheet's next number, locking the sheet first so the claim is read only
        // once no other open on the sheet is under way; a refusal rolls the number back.
        Long opened = next == ClaimStatus.OPEN ? sheets.nextClaimNumber(sheetId) : null;
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!claim.getStatus().canBecome(next)) {
            throw new ClaimStatusRefusedException();
        }
        Long number = claim.getNumber();
        switch (next) {
            case OPEN -> {
                number = opened;
                claim.setNumber(number);
            }
            case VOID -> claim.setNumber(null);
            default -> {}
        }
        claim.setStatus(next);
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        record(event, sheetId, user, person, saved, number);
        return saved;
    }

    private void record(String event, String sheetId, String user, Person person, Claim claim) {
        record(event, sheetId, user, person, claim, claim.getNumber());
    }

    /** A claim's audit line; one about a claim with a number ends with it. */
    private void record(String event, String sheetId, String user, Person person, Claim claim, Long number) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user.strip() + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : ""));
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }
}
