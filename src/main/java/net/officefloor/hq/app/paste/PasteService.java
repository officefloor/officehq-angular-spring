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
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds a DRAFT claim per pasted line of name,amount,date,note for a person named on the sheet.
 *  Every line is checked first; the whole paste is one change, so one refused line adds nothing. */
@Service
public class PasteService {

    /** The most a claim's note holds, as on the claim form. */
    private static final int NOTE_MAX = 2000;

    /** A pasted line, read: the person it names and the claim's figures (a null date: the sheet's entry date, else its today). */
    private record Line(Person person, BigDecimal amount, LocalDate date, String note) {
    }

    private final PersonRepository people;
    private final ClaimService claims;
    private final RollupService rollups;

    public PasteService(PersonRepository people, ClaimService claims, RollupService rollups) {
        this.people = people;
        this.claims = claims;
        this.rollups = rollups;
    }

    /** Add the pasted claims in the order of their lines; blank lines are skipped. */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        int at = 0;
        for (String raw : text.split("\\r?\\n")) {
            at++;
            if (!raw.isBlank()) {
                lines.add(read(sheetId, at, raw));
            }
        }
        if (lines.isEmpty()) {
            throw new PasteRefusedException("Nothing to add");
        }
        return rollups.track(sheetId, user, () -> lines.stream()
                .map(l -> claims.add(sheetId, user, l.person().getId(), l.amount(), l.date(), l.note(), null))
                .toList());
    }

    /** One line as the claim form reads it: an amount of 0.00 or more with up to two decimals, a
     *  date (blank: the sheet's entry date, else its today) and a note (absent: empty) that may hold commas. */
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
        if (note.length() > NOTE_MAX) {
            throw new PasteRefusedException("Line " + at + ": the note is too long");
        }
        return new Line(person, amount, date, note);
    }
}
