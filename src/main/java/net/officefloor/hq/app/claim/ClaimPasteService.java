package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The paste box: lines of {@code name,amount,date,note}, each a DRAFT claim for the person of that
 * name (compared without regard to case), added in order. The date may be blank (the sheet's today)
 * and the note may be absent or hold commas. Every line is held to exactly the claim form's rules;
 * the paste is one change, so a refused line adds nothing and writes nothing. Blank lines are
 * skipped; a paste with no lines is refused.
 */
@Service
public class ClaimPasteService {

    /** The longest note the claim form takes. */
    private static final int NOTE_MAX = 1000;

    private record Line(Person person, BigDecimal amount, LocalDate date, String note) {
    }

    private final PersonRepository people;
    private final ClaimService claims;

    public ClaimPasteService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        String[] raw = text.split("\\r?\\n|\\r");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                lines.add(parse(sheetId, i + 1, raw[i]));
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of name,amount,date,note.");
        }
        List<Claim> added = new ArrayList<>();
        for (Line line : lines) {
            added.add(claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note(), null));
        }
        return added;
    }

    private Line parse(String sheetId, int number, String text) {
        String[] fields = text.split(",", 4);
        if (fields.length < 2) {
            throw refused("Line " + number + " needs at least a name and an amount.");
        }
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null : people.findBySheetIdAndNameIgnoreCase(sheetId, name).orElse(null);
        if (person == null) {
            throw refused("Line " + number + ": no one named \"" + name + "\" is on this sheet.");
        }
        String amount = fields[1].trim();
        if (!amount.matches(ClaimController.SIGNED_AMOUNT)) {
            throw refused("Line " + number + ": enter an amount with up to two decimals.");
        }
        LocalDate date = null;
        String dateText = fields.length > 2 ? fields[2].trim() : "";
        if (!dateText.isEmpty()) {
            try {
                date = LocalDate.parse(dateText);
            } catch (DateTimeParseException e) {
                throw refused("Line " + number + ": enter the date as YYYY-MM-DD, or leave it blank.");
            }
        }
        String note = fields.length > 3 ? fields[3].trim() : "";
        if (note.length() > NOTE_MAX) {
            throw refused("Line " + number + ": the note is too long.");
        }
        return new Line(person, ClaimController.amount(amount), date, note);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
