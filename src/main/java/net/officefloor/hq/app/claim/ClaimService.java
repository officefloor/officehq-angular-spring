package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The claims people on each sheet make. A claim left without a date is dated the sheet's today. No
 * claim dated inside a closed period can be added, edited or change status. */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RollupService rollup;
    private final SheetService sheets;
    private final PeriodRepository periods;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RollupService rollup, SheetService sheets,
            PeriodRepository periods, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.rollup = rollup;
        this.sheets = sheets;
        this.periods = periods;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate dated = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, dated);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, dated, noteOf(note)));
        record("CLAIM_ADDED", user, person, claim, claim.getNumber());
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    @Transactional
    public Claim update(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        LocalDate dated = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDate());
        refuseClosed(sheetId, dated);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setDate(dated);
        claim.setNote(noteOf(note));
        claims.flush();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        record("CLAIM_EDITED", user, person, claim, claim.getNumber());
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** Move the claim to {@code next}; refused when its current status does not allow it. Settling it
     * fixes what is due on it then (the figure it is bound to, when a statement bound it, otherwise
     * its person's rate then); the user who opened it cannot settle it. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus next) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next)) throw new ClaimStatusChangeException();
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) throw new ClaimSelfSettleException();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Integer number = claim.getNumber();
        if (next == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
            claim.setOpenedBy(user);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        if (next == ClaimStatus.SETTLED) claim.setSettledDue(claim.isStated() ? claim.getStatedDue() : rollup.dueOf(claim, person));
        claim.setStatus(next);
        claim.setNumber(next == ClaimStatus.VOID ? null : number);
        claims.flush();
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        if (next == ClaimStatus.SETTLED) {
            BigDecimal due = claim.getSettledDue();
            record(event, user, person, claim, number, "due=" + (due == null ? "n/a" : due.toPlainString()));
        } else {
            record(event, user, person, claim, number);
        }
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** The figure the claim shows and totals now; null when it has no figure (see
     * {@link RollupService#figureOf}). */
    public BigDecimal figureOf(Claim claim, Person person) {
        return rollup.figureOf(claim, person);
    }

    /** Record a change to the claim; {@code number}, the number it has (or had), follows when there is one,
     * then any {@code extra} fields. */
    private void record(String event, String user, Person person, Claim claim, Integer number, String... extra) {
        List<String> fields = new ArrayList<>(List.of("person=" + person.getName(),
                "amount=" + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                "date=" + claim.getDate()));
        if (number != null) fields.add("number=" + number);
        fields.addAll(List.of(extra));
        audit.change(event, claim.getSheetId(), user, fields.toArray(String[]::new));
    }

    /** Refuse a change that touches {@code date} when a closed period of the sheet contains it. */
    private void refuseClosed(String sheetId, LocalDate date) {
        if (periods.closedOn(sheetId, date)) throw new ClaimPeriodClosedException();
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
