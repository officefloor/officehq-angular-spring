package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The paste box: lines of name,amount,date,note each add a DRAFT claim, in order, for the named
 * person on the sheet. The date may be blank (the sheet's entry date while set, else its today) and the note, which may hold commas,
 * may be absent. Every line is held to the claim form's rules; it is one change, so when any line is
 * refused nothing is added and nothing is recorded.
 */
@Service
public class PasteService {

    /** The claim form's amount: up to two decimals (a paste names no claim it corrects). */
    private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,12}(\\.\\d{1,2})?");

    /** The longest note the claim form takes. */
    private static final int NOTE_MAX = 500;

    /** The most lines one paste takes. */
    static final int LINES_MAX = 1000;

    /** A line read from the paste, checked against the form's rules. */
    private record Line(Person person, BigDecimal amount, LocalDate date, String note) {
    }

    private final PersonRepository people;
    private final ClaimService claims;

    public PasteService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    /** Add a DRAFT claim per line of the text, in order; refused as a whole when any line is refused. */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        String[] raw = text == null ? new String[0] : text.split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                lines.add(line(sheetId, i + 1, raw[i]));
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of name,amount,date,note.");
        }
        if (lines.size() > LINES_MAX) {
            throw refused("Paste at most " + LINES_MAX + " lines at once.");
        }
        List<Claim> added = new ArrayList<>();
        for (Line line : lines) {
            added.add(claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note(), null));
        }
        return added;
    }

    private Line line(String sheetId, int number, String text) {
        String[] fields = text.split(",", 4);
        if (fields.length < 2) {
            throw refused("Line " + number + " needs at least a name and an amount.");
        }
        String name = fields[0].trim();
        if (name.isEmpty()) {
            throw refused("Line " + number + " has no name.");
        }
        Person person = people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .orElseThrow(() -> refused("Line " + number + ": " + name + " is not on this sheet."));
        String amount = fields[1].trim();
        if (!AMOUNT.matcher(amount).matches()) {
            throw refused("Line " + number + ": the amount must have up to two decimals.");
        }
        LocalDate date = null;
        String dated = fields.length > 2 ? fields[2].trim() : "";
        if (!dated.isEmpty()) {
            try {
                date = LocalDate.parse(dated);
            } catch (DateTimeParseException e) {
                throw refused("Line " + number + ": the date must be YYYY-MM-DD.");
            }
        }
        String note = fields.length > 3 ? fields[3].trim() : "";
        if (note.length() > NOTE_MAX) {
            throw refused("Line " + number + ": the note is longer than " + NOTE_MAX + " characters.");
        }
        return new Line(person, new BigDecimal(amount), date, note);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
