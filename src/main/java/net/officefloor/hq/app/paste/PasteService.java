package net.officefloor.hq.app.paste;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimNotEditableException;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds a claim per pasted line of {@code name,amount,date,note}, in order, for the person on the
 * sheet with that name (compared as names are), and opens it as the user, as the open button does. The
 * date may be blank (the sheet's entry date); the note may hold commas, or be left out. Every line is
 * checked first; then each is added (or updated) and opened in turn, so claims are numbered in paste
 * order. A line whose person, date and note match one of that person's claims as they stood before the
 * paste updates that claim instead: a DRAFT is edited to the line's amount (only when it differs) and
 * opened; a claim in any other status is refused. Each line is held to the claim form's and the open
 * button's rules; it is one change: when any line is refused nothing is added, edited or opened, and
 * nothing is recorded. Blank lines are skipped. A note ending in {@code " #<reference>"} gives the claim
 * that external reference, which is not part of the note. */
@Service
public class PasteService {

    /** An amount as the claim form takes it. */
    private static final Pattern AMOUNT = Pattern.compile("\\s*-?\\d{1,10}(\\.\\d{1,2})?\\s*");

    /** The longest note the claim form takes. */
    private static final int NOTE_MAX = 500;

    /** The longest reference the claim form takes. */
    private static final int REF_MAX = 120;

    /** A checked line: its person, amount, date (null for the default), note and reference, and the
     * claim it updates (null when it adds one). */
    private record Line(Person person, BigDecimal amount, LocalDate date, String note, String ref, Claim match) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final SheetService sheets;

    public PasteService(PersonRepository people, ClaimRepository claims, ClaimService claimService, SheetService sheets) {
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.sheets = sheets;
    }

    /** The claims added or updated, each opened, in the order of their lines. */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Claim> before = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        LocalDate entry = null;
        List<Line> checked = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            String[] fields = line.split(",", 4);
            if (fields.length < 2 || fields[0].isBlank() || !AMOUNT.matcher(fields[1]).matches()) {
                throw new PasteLineException();
            }
            Person person = people.findBySheetIdAndNameOrAlias(sheetId, fields[0])
                    .orElseThrow(PersonNotFoundException::new);
            String note = fields.length > 3 ? fields[3].trim() : "";
            String ref = null;
            int hash = note.lastIndexOf(" #");
            if (hash >= 0 && !note.substring(hash + 2).isBlank()) {
                ref = note.substring(hash + 2).trim();
                note = note.substring(0, hash).trim();
            }
            if (note.length() > NOTE_MAX || (ref != null && ref.length() > REF_MAX)) throw new PasteLineException();
            BigDecimal amount = new BigDecimal(fields[1].trim()).setScale(2);
            LocalDate date = dateOf(fields);
            if (date == null && entry == null) entry = sheets.entryDate(sheetId);
            LocalDate dated = date != null ? date : entry;
            String matchNote = note;
            Claim match = before.stream()
                    .filter(c -> c.getPersonId().equals(person.getId()) && dated.equals(c.getDate())
                            && matchNote.equals(c.getNote() == null ? "" : c.getNote().trim()))
                    .findFirst().orElse(null);
            if (match != null && match.getStatus() != ClaimStatus.DRAFT) throw new ClaimNotEditableException();
            checked.add(new Line(person, amount, date, note, ref, match));
        }
        if (checked.isEmpty()) throw new PasteLineException();
        List<Claim> pasted = new ArrayList<>();
        for (Line line : checked) {
            Claim claim = line.match();
            if (claim == null) {
                claim = claimService.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note(),
                        null, line.ref());
            } else if (claim.getAmount().compareTo(line.amount()) != 0
                    || (line.ref() != null && !line.ref().equals(claim.getRef()))) {
                String tags = claim.getTags().isEmpty() ? null : String.join(",", claim.getTags());
                claim = claimService.update(sheetId, user, claim.getId(), line.amount(), claim.getDate(),
                        claim.getNote(), claim.getCorrects(), line.ref() != null ? line.ref() : claim.getRef(), tags);
            }
            pasted.add(claimService.changeStatus(sheetId, user, claim.getId(), ClaimStatus.OPEN));
        }
        return pasted;
    }

    /** The line's date; null when it is blank or left out. */
    private static LocalDate dateOf(String[] fields) {
        if (fields.length < 3 || fields[2].isBlank()) return null;
        try {
            return LocalDate.parse(fields[2].trim());
        } catch (DateTimeParseException e) {
            throw new PasteLineException();
        }
    }
}
