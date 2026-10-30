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
 * Paste claims: lines of {@code name,amount,date,note} (the note may hold commas and may be left out; a
 * blank date takes the sheet's entry date while set, else its today), each a DRAFT claim for the person of that name on the sheet,
 * matched as the person form matches names. Every line is held to the claim form's rules and the whole
 * paste is one change: a refused line adds nothing.
 */
@Service
public class PasteClaimsService {

    private static final Pattern AMOUNT = Pattern.compile("\\d{1,12}(\\.\\d{1,2})?");
    private static final int NOTE_MAX = 500;

    private record Line(Person person, BigDecimal amount, LocalDate date, String note) {
    }

    private final PersonRepository people;
    private final ClaimService claims;

    public PasteClaimsService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    /** Add a claim per line, in order; returns how many were added. */
    @Transactional
    public int paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            if (!raw.isBlank()) lines.add(parse(sheetId, raw));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Paste at least one line");
        }
        for (Line line : lines) {
            claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note());
        }
        return lines.size();
    }

    private Line parse(String sheetId, String raw) {
        String[] f = raw.split(",", 4);
        if (f.length < 2) throw refused(raw, "give a name and an amount");
        String name = f[0].trim();
        Person person = name.isEmpty() ? null
                : people.findBySheetIdAndNameIgnoreCase(sheetId, name).orElse(null);
        if (person == null) throw refused(raw, "no one named \"" + name + "\" is on the sheet");
        String amount = f[1].trim();
        if (!AMOUNT.matcher(amount).matches()) throw refused(raw, "the amount must have up to two decimals");
        LocalDate date = null;
        if (f.length > 2 && !f[2].isBlank()) {
            try {
                date = LocalDate.parse(f[2].trim());
            } catch (DateTimeParseException e) {
                throw refused(raw, "the date must be YYYY-MM-DD");
            }
        }
        String note = f.length > 3 ? f[3].trim() : "";
        if (note.length() > NOTE_MAX) throw refused(raw, "the note is too long");
        return new Line(person, new BigDecimal(amount), date, note);
    }

    private static ResponseStatusException refused(String line, String why) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Line \"" + line + "\": " + why);
    }
}
