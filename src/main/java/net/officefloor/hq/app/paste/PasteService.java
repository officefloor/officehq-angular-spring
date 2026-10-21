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

@Service
public class PasteService {

    /** The claim form's amount: up to two decimals; a negative one is a correction, which a line cannot name. */
    private static final String AMOUNT = "-?\\d{1,10}(\\.\\d{1,2})?";
    /** The claim form's longest note. */
    private static final int NOTE_MAX = 1000;

    /** A line read from the box: the person it names and the claim's amount, date (none for today) and note. */
    private record Line(Person person, BigDecimal amount, LocalDate date, String note) {}

    private final PersonRepository people;
    private final ClaimService claims;

    public PasteService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    /** Add a draft claim per line of name,amount,date,note, in order, as the claim form would add it: the note
     * may hold commas or be absent, a blank date is the sheet's entry date (its today when none), and the name must be on the sheet. Every
     * line is read first; it is one change, so one line refused (here or by the claim form's rules) adds none.
     * Blank lines are skipped; a box with no line is refused. */
    @Transactional
    public List<Claim> paste(String user, String sheetId, String text) {
        List<Line> lines = new ArrayList<>();
        String[] raw = text.split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                lines.add(read(sheetId, raw[i], i + 1));
            }
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line.");
        }
        List<Claim> added = new ArrayList<>();
        for (Line line : lines) {
            added.add(claims.add(user, sheetId, line.person().getId(), line.amount(), line.date(), line.note(), null));
        }
        return added;
    }

    private Line read(String sheetId, String line, int number) {
        String[] fields = line.split(",", 4);
        if (fields.length < 2) {
            throw refused(number, "needs a name and an amount.");
        }
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null : people.findByName(sheetId, name).orElse(null);
        if (person == null) {
            throw refused(number, "names no one on the sheet.");
        }
        String amount = fields[1].trim();
        if (!amount.matches(AMOUNT)) {
            throw refused(number, "needs an amount with up to two decimals.");
        }
        LocalDate date = null;
        if (fields.length > 2 && !fields[2].isBlank()) {
            try {
                date = LocalDate.parse(fields[2].trim());
            } catch (DateTimeParseException e) {
                throw refused(number, "needs a date as yyyy-mm-dd, or none.");
            }
        }
        String note = fields.length > 3 ? fields[3] : "";
        if (note.length() > NOTE_MAX) {
            throw refused(number, "has a note longer than " + NOTE_MAX + " characters.");
        }
        return new Line(person, new BigDecimal(amount), date, note);
    }

    private static ResponseStatusException refused(int number, String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + number + " " + why);
    }
}
