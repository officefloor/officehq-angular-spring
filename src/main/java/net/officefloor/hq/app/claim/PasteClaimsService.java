package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
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
 * matched as the person form matches names. A note ending in {@code " #<reference>"} gives the claim that external
 * reference, which is not part of the note. Every line is held to the claim form's rules, a reference also to
 * being unused on the sheet and in the paste, and the whole paste is one change: a refused line adds nothing.
 */
@Service
public class PasteClaimsService {

    private static final Pattern AMOUNT = Pattern.compile("\\d{1,12}(\\.\\d{1,2})?");
    private static final Pattern NOTE_REF = Pattern.compile("(.*) #(\\S.*)");
    private static final int NOTE_MAX = 500;
    private static final int REF_MAX = 100;

    private record Line(Person person, BigDecimal amount, LocalDate date, String note, String ref) {
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
        Set<String> refs = new HashSet<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            Line line = parse(sheetId, raw);
            if (line.ref() != null) {
                try {
                    claims.requireFreeRef(sheetId, line.ref(), null);
                } catch (ResponseStatusException e) {
                    throw refused(raw, "the reference \"" + line.ref() + "\" is already used on the sheet");
                }
                if (!refs.add(line.ref().toLowerCase(Locale.ROOT))) {
                    throw refused(raw, "the reference \"" + line.ref() + "\" is given twice in the paste");
                }
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Paste at least one line");
        }
        for (Line line : lines) {
            claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note(),
                    null, line.ref());
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
        String ref = null;
        Matcher tail = NOTE_REF.matcher(note);
        if (tail.matches()) {
            note = tail.group(1).trim();
            ref = tail.group(2).strip();
        }
        if (note.length() > NOTE_MAX) throw refused(raw, "the note is too long");
        if (ref != null && ref.length() > REF_MAX) throw refused(raw, "the reference is too long");
        return new Line(person, new BigDecimal(amount), date, note, ref);
    }

    private static ResponseStatusException refused(String line, String why) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Line \"" + line + "\": " + why);
    }
}
