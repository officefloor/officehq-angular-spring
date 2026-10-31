package net.officefloor.hq.app.paste;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The paste box: a DRAFT claim per pasted line, for a person already on the sheet. */
@Service
public class PasteService {

    /** An amount as the claim form takes it: up to twelve digits and two decimals, negative for a correction. */
    private static final java.util.regex.Pattern AMOUNT = java.util.regex.Pattern.compile("-?\\d{1,12}(\\.\\d{1,2})?");

    /** The longest note the claim form takes. */
    private static final int NOTE_MAX = 1000;

    private final PersonRepository people;
    private final ClaimService claims;

    public PasteService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    /** A pasted line read into a claim's values. */
    private record Line(Person person, BigDecimal amount, LocalDate date, String note) {
    }

    /**
     * Add a DRAFT claim per line of {@code text} (name,amount,date,note; the note may hold commas, a blank date
     * takes the sheet's entry date while set, else its today, the note may be absent), in order, for the person on the sheet whose name matches
     * (trimmed, ignoring case). Each line is checked by exactly the claim form's rules; one change: when any line
     * is refused, nothing is added and nothing is recorded. Blank lines are skipped; a paste with no lines is
     * refused.
     */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            if (!raw.isBlank()) {
                lines.add(read(sheetId, raw));
            }
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        List<Claim> added = new ArrayList<>();
        for (Line line : lines) {
            added.add(claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note(), null));
        }
        return added;
    }

    private Line read(String sheetId, String raw) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 2) {
            throw refused("Each line needs at least a name and an amount");
        }
        String name = fields[0].trim();
        if (name.isEmpty()) {
            throw refused("Each line needs a name");
        }
        Person person = people.findBySheetIdAndNameIgnoreCase(sheetId, name)
                .orElseThrow(() -> refused("No one named " + name + " is on this sheet"));
        String amount = fields[1].trim();
        if (!AMOUNT.matcher(amount).matches()) {
            throw refused("An amount takes up to two decimals");
        }
        LocalDate date = null;
        String dateText = fields.length > 2 ? fields[2].trim() : "";
        if (!dateText.isEmpty()) {
            try {
                date = LocalDate.parse(dateText);
            } catch (DateTimeParseException e) {
                throw refused("A date is written yyyy-mm-dd");
            }
        }
        String note = fields.length > 3 ? fields[3] : "";
        if (note.length() > NOTE_MAX) {
            throw refused("A note is at most " + NOTE_MAX + " characters");
        }
        return new Line(person, new BigDecimal(amount), date, note);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
