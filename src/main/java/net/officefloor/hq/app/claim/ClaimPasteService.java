package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The paste box: lines of name,amount,date,note, each adding a claim for the named person and
 *  opening it, or updating and opening a draft of theirs with the same date and note. A note ending
 *  in " #reference" gives the claim that external reference, which is then no part of the note. */
@Service
public class ClaimPasteService {

    /** The claim form's amount: up to two decimals, a negative one being a correction. */
    private static final Pattern AMOUNT = Pattern.compile("\\s*-?\\d{1,12}(\\.\\d{1,2})?\\s*");

    /** The claim form's longest note. */
    private static final int NOTE_MAX = 500;

    /** The claim form's longest reference. */
    private static final int REF_MAX = 120;

    /** One parsed line: the person, the amount, the date (null: the sheet's today), the note and the
     *  external reference (null: none). */
    private record Line(Person person, BigDecimal amount, LocalDate date, String note, String ref) {
    }

    private final PersonRepository people;
    private final ClaimService claims;
    private final SheetService sheets;

    public ClaimPasteService(PersonRepository people, ClaimService claims, SheetService sheets) {
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
    }

    /** Add and open a claim per line, in order (numbered in paste order), checking every line first;
     *  one change, so when any line is refused, or any open is refused as the open button would
     *  refuse it, nothing is changed and nothing is recorded. A line whose person, date and note
     *  match a claim of theirs as it stood before the paste (not void or deleted) updates the newest
     *  such draft to the line's amount and opens it instead; a line whose matches hold no draft is
     *  refused. Blank lines are skipped; a paste with no lines is refused, and so is one with a
     *  reference already used on the sheet or by an earlier line, comparing without regard to case.
     *  Returns how many lines went through. */
    @Transactional
    public int paste(String sheetId, String user, String text) {
        List<Claim> before = claims.list(sheetId);
        LocalDate entryDay = sheets.entryDay(sheetId);
        List<Line> lines = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        List<Claim> drafts = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        Set<String> refs = new HashSet<>();
        int n = 0;
        for (String raw : text.split("\\R")) {
            n++;
            if (!raw.isBlank()) {
                Line line = parse(sheetId, n, raw);
                if (line.ref() != null) {
                    if (!refs.add(line.ref().toUpperCase(Locale.ROOT))) {
                        throw refused(n, "the reference \"" + line.ref() + "\" is repeated in the paste");
                    }
                    try {
                        claims.refuseRefUsed(sheetId, line.ref(), null);
                    } catch (ResponseStatusException e) {
                        throw refused(n, "the reference \"" + line.ref() + "\" is already used on this sheet");
                    }
                }
                LocalDate on = line.date() != null ? line.date() : entryDay;
                List<Claim> matches = before.stream()
                        .filter(c -> c.getPersonId().equals(line.person().getId()) && on.equals(c.getDate())
                                && line.note().equals(Objects.requireNonNullElse(c.getNote(), "").trim())
                                && c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                        .toList();
                Claim draft = null;
                if (!matches.isEmpty()) {
                    // the claims are listed newest first
                    draft = matches.stream()
                            .filter(c -> c.getStatus() == ClaimStatus.DRAFT && !taken.contains(c.getId()))
                            .findFirst().orElse(null);
                    if (draft == null) {
                        throw refused(n, "the claim with this date and note is no longer a draft");
                    }
                    taken.add(draft.getId());
                }
                lines.add(line);
                numbers.add(n);
                drafts.add(draft);
            }
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            Claim draft = drafts.get(i);
            try {
                long claimId;
                if (draft == null) {
                    claimId = claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(),
                            line.note(), null, line.ref()).getId();
                } else {
                    claimId = draft.getId();
                    if (draft.getAmount().compareTo(line.amount()) != 0) {
                        claims.editAmount(sheetId, user, claimId, line.amount());
                    }
                }
                claims.changeStatus(sheetId, user, claimId, ClaimStatus.OPEN);
            } catch (ResponseStatusException e) {
                throw new ResponseStatusException(e.getStatusCode(),
                        "Line " + numbers.get(i) + ": " + Objects.requireNonNullElse(e.getReason(), "refused"));
            }
        }
        return lines.size();
    }

    /** A line of name,amount,date,note: the note may hold commas or be absent, the date may be blank. */
    private Line parse(String sheetId, int n, String raw) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 3) {
            throw refused(n, "expected name,amount,date,note");
        }
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null
                : people.findByNameOrAlias(sheetId, name).orElse(null);
        if (person == null) {
            throw refused(n, "no one named \"" + name + "\" on this sheet");
        }
        if (!AMOUNT.matcher(fields[1]).matches()) {
            throw refused(n, "the amount must be a number with up to two decimals");
        }
        BigDecimal amount = new BigDecimal(fields[1].trim()).setScale(2);
        LocalDate date;
        try {
            date = fields[2].isBlank() ? null : LocalDate.parse(fields[2].trim());
        } catch (DateTimeParseException e) {
            throw refused(n, "the date must be YYYY-MM-DD");
        }
        String note = fields.length < 4 ? "" : fields[3].trim();
        String ref = null;
        int hash = note.lastIndexOf(" #");
        if (hash >= 0 && !note.substring(hash + 2).isBlank()) {
            ref = note.substring(hash + 2).trim();
            note = note.substring(0, hash).trim();
        }
        if (note.length() > NOTE_MAX) {
            throw refused(n, "the note is too long");
        }
        if (ref != null && ref.length() > REF_MAX) {
            throw refused(n, "the reference is too long");
        }
        return new Line(person, amount, date, note, ref);
    }

    private static ResponseStatusException refused(int n, String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": " + why);
    }
}
