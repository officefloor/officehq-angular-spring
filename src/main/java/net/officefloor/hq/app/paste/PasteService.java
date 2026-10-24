package net.officefloor.hq.app.paste;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds a DRAFT claim per pasted line of {@code name,amount,date,note}, in order, for the person on the
 * sheet with that name (compared as names are). The date may be blank (the sheet's today); the note may
 * hold commas, or be left out. Each line is held to the claim form's rules; it is one change: when any
 * line is refused no claim is added, and nothing is recorded. Blank lines are skipped. A note ending in
 * {@code " #<reference>"} gives the claim that external reference, which is not part of the note. */
@Service
public class PasteService {

    /** An amount as the claim form takes it. */
    private static final Pattern AMOUNT = Pattern.compile("\\s*-?\\d{1,10}(\\.\\d{1,2})?\\s*");

    /** The longest note the claim form takes. */
    private static final int NOTE_MAX = 500;

    /** The longest reference the claim form takes. */
    private static final int REF_MAX = 120;

    private final PersonRepository people;
    private final ClaimService claimService;

    public PasteService(PersonRepository people, ClaimService claimService) {
        this.people = people;
        this.claimService = claimService;
    }

    /** The claims added, in the order of their lines. */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Claim> added = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            String[] fields = line.split(",", 4);
            if (fields.length < 2 || fields[0].isBlank() || !AMOUNT.matcher(fields[1]).matches()) {
                throw new PasteLineException();
            }
            Person person = people.findBySheetIdAndNameKey(sheetId, Person.keyOf(fields[0]))
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
            added.add(claimService.add(sheetId, user, person.getId(), amount, dateOf(fields), note, null, ref));
        }
        if (added.isEmpty()) throw new PasteLineException();
        return added;
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
