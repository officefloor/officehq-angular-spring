package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.rollup.RollupService.Snapshot;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RollupService rollup;
    private final SheetService sheets;
    private final PeriodService periods;
    private final SettingService settings;
    private final Audit audit;
    private final TransactionTemplate transaction;

    public ClaimService(ClaimRepository claims, PersonRepository people, RollupService rollup, SheetService sheets,
            PeriodService periods, SettingService settings, Audit audit, TransactionTemplate transaction) {
        this.transaction = transaction;
        this.claims = claims;
        this.people = people;
        this.rollup = rollup;
        this.sheets = sheets;
        this.periods = periods;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a claim without a date is dated the sheet's today. One dated in a closed period
     * is refused, and so is a correction that does not name a settled claim of the same person. */
    @Transactional
    public Claim add(String user, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note,
            Integer corrects) {
        LocalDate today = sheets.today(sheetId);
        Person person = requirePerson(sheetId, personId);
        requireNotRetired(person, date != null ? date : today);
        requireOpenPeriod(sheetId, date != null ? date : today);
        Integer corrected = correctedBy(sheetId, personId, amount, corrects);
        Snapshot before = rollup.snapshot(sheetId);
        Claim fresh = new Claim(sheetId, personId, amount, date != null ? date : today, noteOf(note));
        fresh.setCorrects(corrected);
        Claim claim = claims.saveAndFlush(fresh);
        audit.recordOnCommit(entry("CLAIM_ADDED", user, person, claim));
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** Edit a claim; only a draft can be edited (an open claim is voided and added again instead), and one dated
     * in a closed period, or that would be, is refused, as is a correction that does not name a settled claim of
     * the same person. */
    @Transactional
    public Claim update(String user, String sheetId, Long id, BigDecimal amount, LocalDate date, String note,
            Integer corrects) {
        LocalDate today = sheets.today(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft claim can be edited.");
        }
        requireOpenPeriod(sheetId, claim.getDate());
        requireOpenPeriod(sheetId, date != null ? date : today);
        requireNotRetired(requirePerson(sheetId, claim.getPersonId()), date != null ? date : today);
        Integer corrected = correctedBy(sheetId, claim.getPersonId(), amount, corrects);
        Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setCorrects(corrected);
        claim.setDate(date != null ? date : today);
        claim.setNote(noteOf(note));
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_EDITED", user, requirePerson(sheetId, claim.getPersonId()), claim));
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** Move a claim to another status; a change its status does not allow, or of a claim dated in a closed
     * period, is refused; so is a settle by a clerk of a claim whose due is above the clerk limit. */
    @Transactional
    public Claim changeStatus(String user, String sheetId, Long id, ClaimStatus next) {
        sheets.today(sheetId); // the sheet must exist
        // Taking the number first locks the sheet, so claims are opened one at a time; a refused
        // open rolls back and gives the number back.
        Integer number = next == ClaimStatus.OPEN ? sheets.nextClaimNumber(sheetId) : null;
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        requireOpenPeriod(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        // Four eyes: whoever opened a claim cannot also settle it.
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot settle it.");
        }
        Person person = requirePerson(sheetId, claim.getPersonId());
        // A clerk settles only a claim whose due is within the sheet's clerk limit.
        if (next == ClaimStatus.SETTLED) {
            settings.requireMaySettle(sheetId, user, rollup.dueOf(claim, person));
        }
        Snapshot before = rollup.snapshot(sheetId);
        BigDecimal due = rollup.dueOf(claim, person);
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            claim.setNumber(number);
            claim.setOpenedBy(user);
        }
        // A voided claim's line carries the number it had.
        String entry = entry(EVENTS.get(next), user, person, claim);
        if (next == ClaimStatus.SETTLED) {
            // Settling fixes the due at the figure the claim has: the one its last statement bound it to, else
            // the rate its grade has on its date; with no figure it stays live. The late charge it has (taken
            // while it is still OPEN) is frozen in.
            claim.setSettledDue(due);
            entry += " due=" + (claim.getSettledDue() != null ? claim.getSettledDue().toPlainString() : "n/a");
        }
        claims.flush();
        audit.recordOnCommit(entry);
        rollup.recordChanges(user, sheetId, before);
        return claim;
    }

    /** What opening claims together did: how many were opened and refused, and the opened claims. */
    public record OpenedAll(int opened, int refused, List<Claim> claims) {}

    /** Open the given claims of the sheet one at a time, in list order: people in the order they were added,
     * each one's claims newest first. Each is opened, and numbered, as {@link #changeStatus} would open it on its
     * own; one that cannot be opened (or is not on the sheet) is refused and left as it is. */
    public OpenedAll openAll(String user, String sheetId, List<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        List<Claim> chosen = transaction.execute(status -> {
            sheets.today(sheetId); // the sheet must exist
            Map<Long, Integer> personOrder = new HashMap<>();
            for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
                personOrder.put(person.getId(), personOrder.size());
            }
            return claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                    .filter(c -> wanted.contains(c.getId()))
                    .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE)))
                    .toList();
        });
        List<Claim> opened = new ArrayList<>();
        for (Claim claim : chosen) {
            try {
                opened.add(transaction.execute(status -> changeStatus(user, sheetId, claim.getId(), ClaimStatus.OPEN)));
            } catch (ResponseStatusException refused) {
                // Left as it is: its transaction rolled back, writing nothing.
            }
        }
        return new OpenedAll(opened.size(), wanted.size() - opened.size(), opened);
    }

    private static final Map<ClaimStatus, String> EVENTS = Map.of(
            ClaimStatus.OPEN, "CLAIM_OPENED",
            ClaimStatus.SETTLED, "CLAIM_SETTLED",
            ClaimStatus.VOID, "CLAIM_VOIDED");

    /** The figure a claim has now: what it was settled at, else what its person's last statement bound it to,
     * else what is due at the rate of the grade its person has on its date; none when that is n/a. */
    public BigDecimal figureOf(Claim claim, Person person) {
        return rollup.figureOf(claim, person);
    }

    /** The number a claim of this amount corrects: none unless the amount is negative, when it must name a
     * SETTLED claim of the same person, or the claim is refused. */
    private Integer correctedBy(String sheetId, Long personId, BigDecimal amount, Integer corrects) {
        if (amount.signum() >= 0) {
            return null;
        }
        boolean settled = corrects != null && claims.findBySheetIdAndPersonId(sheetId, personId).stream()
                .anyMatch(c -> corrects.equals(c.getNumber()) && c.getStatus() == ClaimStatus.SETTLED);
        if (!settled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A correction must name a settled claim of the same person.");
        }
        return corrects;
    }

    /** Refuse a change to a claim dated in a closed period. Locks the sheet first, so a period cannot close
     * while the change is made. */
    private void requireOpenPeriod(String sheetId, LocalDate date) {
        sheets.lock(sheetId);
        periods.requireOpen(sheetId, date);
    }

    /** Refuse a claim dated after its person's retirement. */
    private static void requireNotRetired(Person person, LocalDate date) {
        if (person.retiredOn(date)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    person.getName() + " is retired from " + person.getRetiredFrom() + ".");
        }
    }

    private Person requirePerson(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    /** The amount is written as the screen shows it, with two decimals; the date as yyyy-mm-dd; a
     * claim with a number ends with it. */
    private static String entry(String event, String user, Person person, Claim claim) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (claim.getNumber() != null ? " number=" + claim.getNumber() : "");
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
