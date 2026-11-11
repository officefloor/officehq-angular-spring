package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds a DRAFT claim per pasted line of name,amount,date,note, in order. The name must be on the sheet (compared as
 * names are); the amount, date and note are taken as the claim form takes them (a blank date is the sheet's today, the
 * note may hold commas or be absent). The whole is one change: if any line is refused, nothing is added.
 */
@Service
public class PasteClaimsService {

    /** The amount as the claim form takes it. */
    private static final String AMOUNT = "\\s*-?\\d{1,10}(\\.\\d{1,2})?\\s*";

    private static final int MAX_NOTE = 1000;

    private record Line(Long personId, BigDecimal amount, LocalDate date, String note) {
    }

    private final PersonRepository people;
    private final ClaimService claims;

    public PasteClaimsService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    /** How many claims were added. */
    @Transactional
    public int paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        String[] rows = text.split("\\r?\\n");
        for (int i = 0; i < rows.length; i++) {
            if (!rows[i].isBlank()) {
                lines.add(parse(sheetId, i + 1, rows[i]));
            }
        }
        if (lines.isEmpty()) {
            throw new PasteClaimsException("Paste at least one line of name,amount,date,note");
        }
        // Each claim is checked as the claim form's are; a refused one rolls back those added before it.
        for (Line line : lines) {
            claims.add(sheetId, user, line.personId(), line.amount(), line.date(), line.note(), null);
        }
        return lines.size();
    }

    private Line parse(String sheetId, int number, String row) {
        String[] fields = row.split(",", 4);
        if (fields.length < 3) {
            throw new PasteClaimsException(number, "expected name,amount,date,note");
        }
        Person person = people.findBySheetIdAndNameKey(sheetId, Person.key(fields[0]))
                .orElseThrow(() -> new PasteClaimsException(number, "no one named " + fields[0].trim()));
        if (!fields[1].matches(AMOUNT)) {
            throw new PasteClaimsException(number, "the amount is not one with at most two decimals");
        }
        BigDecimal amount = new BigDecimal(fields[1].trim()).setScale(2, RoundingMode.UNNECESSARY);
        LocalDate date;
        try {
            date = fields[2].isBlank() ? null : LocalDate.parse(fields[2].trim());
        } catch (DateTimeParseException e) {
            throw new PasteClaimsException(number, "the date is not yyyy-mm-dd");
        }
        String note = fields.length > 3 ? fields[3] : "";
        if (note.length() > MAX_NOTE) {
            throw new PasteClaimsException(number, "the note is too long");
        }
        return new Line(person.getId(), amount, date, note);
    }
}
