package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pasted claims: lines of name,amount,date,note (the note may hold commas and may be absent; a blank
 * date is the sheet's today), each a DRAFT claim for the person of the name on the sheet (ignoring
 * case and surrounding spaces), added in order exactly as the claim form adds one. Blank lines are
 * skipped; a paste with no line is refused. It is one change: a line that is refused refuses them all.
 */
@Service
public class ClaimPasteService {

    /** The claim form's amount: up to two decimals, negative only for a correction (which a paste cannot name). */
    private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,12}(\\.\\d{1,2})?");
    private static final int NOTE_MAX = 500;

    private record Line(Long personId, BigDecimal amount, LocalDate date, String note) {}

    private final ClaimService claims;
    private final PersonRepository people;

    public ClaimPasteService(ClaimService claims, PersonRepository people) {
        this.claims = claims;
        this.people = people;
    }

    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            lines.add(parse(sheetId, raw));
        }
        if (lines.isEmpty()) throw new ClaimPasteRefusedException();
        List<Claim> added = new ArrayList<>();
        for (Line line : lines) {
            added.add(claims.add(sheetId, user, line.personId(), line.amount(), line.date(), line.note(), null));
        }
        return added;
    }

    private Line parse(String sheetId, String raw) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 3) throw new ClaimPasteRefusedException();
        String name = fields[0].strip();
        String amount = fields[1].strip();
        String date = fields[2].strip();
        String note = fields.length == 4 ? fields[3].strip() : "";
        if (name.isEmpty() || !AMOUNT.matcher(amount).matches() || note.length() > NOTE_MAX) {
            throw new ClaimPasteRefusedException();
        }
        Person person = people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .orElseThrow(ClaimPasteRefusedException::new);
        LocalDate on;
        try {
            on = date.isEmpty() ? null : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new ClaimPasteRefusedException();
        }
        return new Line(person.getId(), new BigDecimal(amount).setScale(2), on, note);
    }
}
