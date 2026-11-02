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

/** The paste box: lines of name,amount,date,note, each adding a draft claim for the named person. */
@Service
public class ClaimPasteService {

    /** The claim form's amount: up to two decimals, a negative one being a correction. */
    private static final Pattern AMOUNT = Pattern.compile("\\s*-?\\d{1,12}(\\.\\d{1,2})?\\s*");

    /** The claim form's longest note. */
    private static final int NOTE_MAX = 500;

    /** One parsed line: the person, the amount, the date (null: the sheet's today) and the note. */
    private record Line(Person person, BigDecimal amount, LocalDate date, String note) {
    }

    private final PersonRepository people;
    private final ClaimService claims;

    public ClaimPasteService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    /** Add a draft claim per line, in order, checking every line first; one change, so when any line
     *  is refused nothing is added and nothing is recorded. Blank lines are skipped; a paste with no
     *  lines is refused. Returns how many claims were added. */
    @Transactional
    public int paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        int n = 0;
        for (String raw : text.split("\\R")) {
            n++;
            if (!raw.isBlank()) {
                lines.add(parse(sheetId, n, raw));
            }
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        for (Line line : lines) {
            claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note());
        }
        return lines.size();
    }

    /** A line of name,amount,date,note: the note may hold commas or be absent, the date may be blank. */
    private Line parse(String sheetId, int n, String raw) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 3) {
            throw refused(n, "expected name,amount,date,note");
        }
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null
                : people.findBySheetIdAndNameIgnoreCase(sheetId, name).orElse(null);
        if (person == null) {
            throw refused(n, "no one named \"" + name + "\" on this sheet");
        }
        if (!AMOUNT.matcher(fields[1]).matches()) {
            throw refused(n, "the amount must be a number with up to two decimals");
        }
        BigDecimal amount = new BigDecimal(fields[1].trim()).setScale(2);
        LocalDate date;
        try {
            date = fields[2].isBlank() ? null : LocalDate.parse(fields[2].trim());
        } catch (DateTimeParseException e) {
            throw refused(n, "the date must be YYYY-MM-DD");
        }
        String note = fields.length < 4 ? "" : fields[3].trim();
        if (note.length() > NOTE_MAX) {
            throw refused(n, "the note is too long");
        }
        return new Line(person, amount, date, note);
    }

    private static ResponseStatusException refused(int n, String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": " + why);
    }
}
