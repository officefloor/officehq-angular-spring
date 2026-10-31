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

/** The paste box: a DRAFT claim per pasted line, for a person already on the sheet. */
@Service
public class PasteService {

    /** An amount as the claim form takes it: up to twelve digits and two decimals, negative for a correction. */
    private static final java.util.regex.Pattern AMOUNT = java.util.regex.Pattern.compile("-?\\d{1,12}(\\.\\d{1,2})?");

    /** The longest note the claim form takes. */
    private static final int NOTE_MAX = 1000;

    /** The longest external reference the claim form takes. */
    private static final int REF_MAX = 120;

    private final PersonRepository people;
    private final ClaimService claims;

    public PasteService(PersonRepository people, ClaimService claims) {
        this.people = people;
        this.claims = claims;
    }

    /** A pasted line read into a claim's values. */
    private record Line(Person person, BigDecimal amount, LocalDate date, String note, String ref) {
    }

    /**
     * Add a DRAFT claim per line of {@code text} (name,amount,date,note; the note may hold commas, a blank date
     * takes the sheet's entry date while set, else its today, the note may be absent), in order, for the person on the sheet whose name or an alias matches
     * (trimmed, ignoring case). Each line is checked by exactly the claim form's rules; one change: when any line
     * is refused, nothing is added and nothing is recorded. Blank lines are skipped; a paste with no lines is
     * refused. A note ending in {@code " #<reference>"} gives the claim that external reference, which is not part of
     * the note; a reference used on the sheet or on an earlier line, without regard to case, refuses the paste.
     */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        for (String raw : text.split("\\r?\\n")) {
            if (!raw.isBlank()) {
                Line line = read(sheetId, raw);
                if (line.ref() != null && !refs.add(line.ref().toLowerCase(Locale.ROOT))) {
                    throw ClaimService.refUsed(line.ref());
                }
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        List<Claim> added = new ArrayList<>();
        for (Line line : lines) {
            added.add(claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note(), null,
                    line.ref()));
        }
        return added;
    }

    private Line read(String sheetId, String raw) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 2) {
            throw refused("Each line needs at least a name and an amount");
        }
        String name = fields[0].trim();
        if (name.isEmpty()) {
            throw refused("Each line needs a name");
        }
        Person person = people.findBySheetIdAndNameOrAliasIgnoreCase(sheetId, name)
                .orElseThrow(() -> refused("No one named " + name + " is on this sheet"));
        String amount = fields[1].trim();
        if (!AMOUNT.matcher(amount).matches()) {
            throw refused("An amount takes up to two decimals");
        }
        LocalDate date = null;
        String dateText = fields.length > 2 ? fields[2].trim() : "";
        if (!dateText.isEmpty()) {
            try {
                date = LocalDate.parse(dateText);
            } catch (DateTimeParseException e) {
                throw refused("A date is written yyyy-mm-dd");
            }
        }
        String note = fields.length > 3 ? fields[3] : "";
        if (note.length() > NOTE_MAX) {
            throw refused("A note is at most " + NOTE_MAX + " characters");
        }
        String ref = null;
        String trimmed = note.trim();
        int hash = trimmed.lastIndexOf(" #");
        if (hash >= 0 && !trimmed.substring(hash + 2).isBlank()) {
            ref = ClaimService.refOf(trimmed.substring(hash + 2));
            note = trimmed.substring(0, hash);
        }
        if (ref != null && ref.length() > REF_MAX) {
            throw refused("A reference is at most " + REF_MAX + " characters");
        }
        return new Line(person, new BigDecimal(amount), date, note, ref);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
