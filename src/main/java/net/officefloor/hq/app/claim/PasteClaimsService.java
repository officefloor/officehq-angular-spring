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

@Service
public class PasteClaimsService {

    /** A claim's amount as the claim form takes it. */
    private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,13}(\\.\\d{1,2})?");

    /** The longest note the claim form takes. */
    private static final int NOTE_MAX = 1000;

    private final PersonRepository people;
    private final ClaimService claims;

    public PasteClaimsService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    /** Add a DRAFT claim for each line of name,amount,date,note, in order: the name of a person on
     *  the sheet (ignoring case and surrounding spaces), an amount, a date (blank: the sheet's
     *  entry date while set, else its today) and a note that may hold commas or be absent; blank lines are skipped. Each line is
     *  taken by the claim form's rules; one change, so when any line is refused (400 when it is not
     *  a claim the form takes, 422 when its name is not on the sheet, else as the claim form
     *  refuses it) nothing is added. */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<String[]> lines = new ArrayList<>();
        for (String line : text.split("\\r?\\n|\\r")) {
            if (!line.isBlank()) lines.add(line.split(",", 4));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        List<Claim> added = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String[] f = lines.get(i);
            int n = i + 1;
            if (f.length < 2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": give a name and an amount");
            }
            String name = f[0].trim();
            String amount = f[1].trim();
            String date = f.length > 2 ? f[2].trim() : "";
            String note = f.length > 3 ? f[3].trim() : "";
            if (name.isEmpty() || !AMOUNT.matcher(amount).matches() || note.length() > NOTE_MAX) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": not a claim the form takes");
            }
            LocalDate on;
            try {
                on = date.isEmpty() ? null : LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": the date is not yyyy-mm-dd");
            }
            Person person = people.findBySheetIdAndNameIgnoreCase(sheetId, name).stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Line " + n + ": no person named " + name));
            added.add(claims.add(sheetId, user, person.getId(), new BigDecimal(amount), on, note, null));
        }
        return added;
    }
}
