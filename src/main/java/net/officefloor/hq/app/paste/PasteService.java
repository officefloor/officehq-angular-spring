package net.officefloor.hq.app.paste;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Adds a draft claim for each pasted line of name,amount,date,note, all of them or none. A note ending in
 * " #reference" gives the claim that external reference, and the note is what comes before it.
 */
@Service
public class PasteService {

    /** A pasted line read into the claim form's fields, for a person on the sheet. */
    private record Line(Person person, BigDecimal amount, LocalDate date, String note, String ref) {
    }

    private final PersonRepository people;
    private final ClaimService claims;

    public PasteService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    /**
     * Reads every line first by the claim form's rules, then adds a claim per line in order, each by the rules of
     * adding one claim. One change: any line refused refuses them all, and nothing is stored or audited.
     */
    @Transactional
    public List<Claim> paste(String by, String sheetId, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        int n = 0;
        for (String raw : text.split("\\r?\\n")) {
            n++;
            if (raw.isBlank()) {
                continue;
            }
            Line line = read(sheetId, n, raw);
            if (line.ref() != null && !line.ref().isEmpty() && !refs.add(line.ref().toLowerCase(Locale.ROOT))) {
                throw refused("Line " + n + " repeats the reference " + line.ref() + ".");
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of name,amount,date,note.");
        }
        List<Claim> added = new ArrayList<>();
        for (Line l : lines) {
            added.add(claims.add(by, sheetId, l.person().getId(), l.amount(), l.date(), l.note(), null, l.ref()));
        }
        return added;
    }

    private Line read(String sheetId, int n, String raw) {
        String[] f = raw.split(",", 4);
        if (f.length < 2) {
            throw refused("Line " + n + " needs at least a name and an amount.");
        }
        String name = f[0].strip();
        Person person = name.isEmpty() ? null
                : people.findBySheetIdAndNameIgnoreCase(sheetId, name).orElse(null);
        if (person == null) {
            throw refused("Line " + n + " names no one on the sheet.");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(f[1].strip());
        } catch (NumberFormatException e) {
            throw refused("Line " + n + " has no amount.");
        }
        // the claim form's amount: up to twelve whole digits and two decimals
        if (amount.scale() > 2 || amount.precision() - amount.scale() > 12) {
            throw refused("Line " + n + " has an amount with more than two decimals.");
        }
        LocalDate date = null;
        String dateText = f.length > 2 ? f[2].strip() : "";
        if (!dateText.isEmpty()) {
            try {
                date = LocalDate.parse(dateText);
            } catch (DateTimeParseException e) {
                throw refused("Line " + n + " has a date that is not yyyy-mm-dd.");
            }
        }
        String note = f.length > 3 ? f[3].strip() : "";
        String ref = null;
        int hash = note.lastIndexOf(" #");
        if (hash >= 0) {
            ref = note.substring(hash + 2).strip();
            note = note.substring(0, hash).strip();
        }
        if (note.length() > 500) {
            throw refused("Line " + n + " has a note longer than 500 characters.");
        }
        if (ref != null && ref.length() > 140) {
            throw refused("Line " + n + " has a reference longer than 140 characters.");
        }
        return new Line(person, amount, date, note, ref);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, reason);
    }
}
