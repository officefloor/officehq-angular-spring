package net.officefloor.hq.app.paste;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opens a claim per pasted line of name,amount,date,note for a person named on the sheet; a
 *  note ending in " #<reference>" gives the claim that external reference. A line naming the person,
 *  date and note of one of their claims as they stood before the paste (not VOID or DELETED) updates
 *  that claim, the newest such DRAFT, to the line's amount instead of adding one; a line whose matches
 *  hold no DRAFT is refused. Each claim is then opened as the open button would. Every line is checked
 *  first; the whole paste is one change, so one refused line, or one refused open, changes nothing. */
@Service
public class PasteService {

    /** The most a claim's note holds, as on the claim form. */
    private static final int NOTE_MAX = 2000;

    /** The most a claim's external reference holds, as on the claim form. */
    private static final int REF_MAX = 255;

    /** A pasted line, read: the person it names and the claim's figures (a null date: the sheet's entry
     *  date, else its today; a null reference: none). */
    private record Line(int at, Person person, BigDecimal amount, LocalDate date, String note, String ref) {
    }

    private final PersonRepository people;
    private final ClaimService claims;
    private final RollupService rollups;
    private final SettingService settings;

    public PasteService(PersonRepository people, ClaimService claims, RollupService rollups,
            SettingService settings) {
        this.people = people;
        this.claims = claims;
        this.rollups = rollups;
        this.settings = settings;
    }

    /** Add or update the pasted claims in the order of their lines, opening each (so they are
     *  numbered in that order); blank lines are skipped. The claims are returned in line order. */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        int at = 0;
        for (String raw : text.split("\\r?\\n")) {
            at++;
            if (!raw.isBlank()) {
                Line line = read(sheetId, at, raw);
                if (line.ref() != null && (!refs.add(Claim.keyOf(line.ref()))
                        || claims.refInUse(sheetId, line.ref(), null))) {
                    throw new PasteRefusedException("Line " + at + ": the reference " + line.ref() + " is already used");
                }
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw new PasteRefusedException("Nothing to add");
        }
        List<Claim> targets = targets(sheetId, lines);
        List<Claim> opened = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            Claim target = targets.get(i);
            opened.add(rollups.track(sheetId, user, () -> {
                Claim claim = target == null
                        ? claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note(), null,
                                line.ref())
                        : edited(sheetId, user, target, line);
                return claims.changeStatus(sheetId, user, claim.getPersonId(), claim.getId(), ClaimStatus.OPEN);
            }));
        }
        return opened;
    }

    /** The claim each line updates, as the claims stood before the paste (null: the line adds one): the
     *  newest DRAFT of the person with the line's date and note; a line matching only claims that are
     *  not DRAFTs, or a DRAFT an earlier line of the paste already updates, is refused. */
    private List<Claim> targets(String sheetId, List<Line> lines) {
        List<Claim> before = claims.list(sheetId);
        LocalDate defaultDate = settings.defaultClaimDate(sheetId);
        Set<Long> taken = new HashSet<>();
        List<Claim> targets = new ArrayList<>();
        for (Line line : lines) {
            LocalDate on = line.date() != null ? line.date() : defaultDate;
            List<Claim> matches = before.stream()
                    .filter(c -> c.getPersonId().equals(line.person().getId()) && on.equals(c.getDate())
                            && line.note().equals(c.getNote())
                            && c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                    .toList();
            if (matches.isEmpty()) {
                targets.add(null);
                continue;
            }
            Claim draft = matches.stream().filter(c -> c.getStatus() == ClaimStatus.DRAFT).findFirst()
                    .orElseThrow(() -> new PasteRefusedException(
                            "Line " + line.at() + ": it matches a claim that is not a DRAFT"));
            if (!taken.add(draft.getId())) {
                throw new PasteRefusedException("Line " + line.at() + ": its DRAFT is updated by an earlier line");
            }
            targets.add(draft);
        }
        return targets;
    }

    /** A matched DRAFT edited to the line's amount (and its reference, when it gives one); left as it
     *  is when that changes nothing. */
    private Claim edited(String sheetId, String user, Claim draft, Line line) {
        String ref = line.ref() != null ? line.ref() : draft.getRef();
        if (draft.getAmount().compareTo(line.amount()) == 0 && Objects.equals(ref, draft.getRef())) {
            return draft;
        }
        return claims.update(sheetId, user, draft.getPersonId(), draft.getId(), line.amount(), draft.getDate(),
                draft.getNote(), draft.getCorrects(), ref, String.join(",", draft.getTags()));
    }

    /** One line as the claim form reads it: an amount of 0.00 or more with up to two decimals, a
     *  date (blank: the sheet's entry date, else its today) and a note (absent: empty) that may hold commas,
     *  ending in " #<reference>" when it gives the claim an external reference. */
    private Line read(String sheetId, int at, String raw) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 3) {
            throw new PasteRefusedException("Line " + at + " needs name,amount,date");
        }
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null : people.findByName(sheetId, name).orElse(null);
        if (person == null) {
            throw new PasteRefusedException("Line " + at + ": no person " + name + " on the sheet");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(fields[1].trim());
        } catch (NumberFormatException e) {
            throw new PasteRefusedException("Line " + at + ": the amount is not a number");
        }
        if (amount.signum() < 0 || amount.scale() > 2 || amount.precision() - amount.scale() > 10) {
            throw new PasteRefusedException("Line " + at + ": the amount must be 0.00 or more, with up to two decimals");
        }
        LocalDate date;
        try {
            date = fields[2].isBlank() ? null : LocalDate.parse(fields[2].trim());
        } catch (DateTimeParseException e) {
            throw new PasteRefusedException("Line " + at + ": the date is not a date");
        }
        String note = fields.length > 3 ? fields[3].trim() : "";
        String ref = null;
        int hash = note.lastIndexOf(" #");
        if (hash >= 0) {
            ref = ClaimService.refOf(note.substring(hash + 2));
            note = note.substring(0, hash).trim();
        }
        if (ref != null && ref.length() > REF_MAX) {
            throw new PasteRefusedException("Line " + at + ": the reference is too long");
        }
        if (note.length() > NOTE_MAX) {
            throw new PasteRefusedException("Line " + at + ": the note is too long");
        }
        return new Line(at, person, amount, date, note, ref);
    }
}
