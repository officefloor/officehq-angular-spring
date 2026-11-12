package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The paste box: lines of name,amount,date,note (the note may hold commas and may be absent; a blank date
 * takes the sheet's entry date, its today when off), each a DRAFT claim for the person on the sheet with
 * the name or an alias (ignoring case), added in order. A note ending in " #<reference>" gives the claim that external
 * reference, which is not part of the note. Blank lines are skipped. One change: every line is checked
 * with the claim form's rules (a reference used twice in the paste too, ignoring case), and a refused line
 * refuses the paste, adding nothing and writing nothing.
 */
@Service
public class PasteService {

    private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,13}(\\.\\d{1,2})?");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final PersonRepository people;
    private final ClaimService claims;

    public PasteService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    private record Line(int number, Person person, BigDecimal amount, LocalDate date, String note,
            String ref) {
    }

    /** The claims added, in the order of their lines. */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        String[] raw = text == null ? new String[0] : text.split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                Line line = parse(sheetId, i + 1, raw[i]);
                if (line.ref() != null && !refs.add(line.ref().toLowerCase(Locale.ROOT))) {
                    throw refused(line.number(), "the reference " + line.ref() + " is already in this paste.");
                }
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw new ClaimPasteException("Paste at least one line of name,amount,date,note.");
        }
        List<Claim> added = new ArrayList<>();
        for (Line line : lines) {
            try {
                added.add(claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note(),
                        null, line.ref(), List.of()));
            } catch (PeriodClosedException | PersonRetiredException | ClaimCorrectionException
                    | ClaimRefException e) {
                throw new ClaimPasteException("Line " + line.number() + ": " + e.getMessage());
            }
        }
        return added;
    }

    private Line parse(String sheetId, int number, String raw) {
        String[] f = raw.split(",", 4);
        String name = f[0].trim();
        if (name.isEmpty() || name.length() > 200) {
            throw refused(number, "enter a name.");
        }
        Person person = people.findByNameOrAlias(sheetId, name)
                .orElseThrow(() -> refused(number, "no one on the sheet is named " + name + "."));
        String amount = f.length > 1 ? f[1].trim() : "";
        if (!AMOUNT.matcher(amount).matches()) {
            throw refused(number, "enter an amount with up to two decimals.");
        }
        String date = f.length > 2 ? f[2].trim() : "";
        LocalDate on = null;
        if (!date.isEmpty()) {
            try {
                on = LocalDate.parse(date, DATE);
            } catch (DateTimeParseException e) {
                throw refused(number, "enter a date as YYYY-MM-DD.");
            }
        }
        String note = f.length > 3 ? f[3].trim() : "";
        String ref = null;
        int hash = note.lastIndexOf(" #");
        if (hash >= 0 && !note.substring(hash + 2).isBlank()) {
            ref = note.substring(hash + 2).trim();
            note = note.substring(0, hash).trim();
        }
        if (note.length() > 1000) {
            throw refused(number, "the note is too long.");
        }
        if (ref != null && ref.length() > 200) {
            throw refused(number, "the reference is too long.");
        }
        return new Line(number, person, new BigDecimal(amount), on, note, ref);
    }

    private static ClaimPasteException refused(int number, String why) {
        return new ClaimPasteException("Line " + number + ": " + why);
    }
}
