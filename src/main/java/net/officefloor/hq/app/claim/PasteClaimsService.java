package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Paste claims: lines of {@code name,amount,date,note} (the note may hold commas and may be left out; a
 * blank date takes the sheet's entry date while set, else its today), each a claim for the person of that name on the sheet,
 * matched as the person form matches names, added and then opened by the user as the open button would, in
 * paste order. A note ending in {@code " #<reference>"} gives the claim that external reference, which is not part of
 * the note. A line whose person, date and note match one of that person's claims as they stood before the paste (void
 * and deleted claims never match) updates that claim instead: the newest matching DRAFT is edited to the line's amount
 * (when it differs) and opened; a line whose matches include no DRAFT is refused. Every line is held to the claim
 * form's and the open button's rules, a reference also to being unused on the sheet and in the paste, and the whole
 * paste is one change: a refused line adds, edits and opens nothing.
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
    private final SheetService sheets;

    public PasteClaimsService(PersonRepository people, ClaimService claims, SheetService sheets) {
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
    }

    /** Add or update, then open, a claim per line, in order; returns how many lines went through. */
    @Transactional
    public int paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        LocalDate claimDate = sheets.claimDate(sheetId);
        Map<Long, List<Claim>> before = new HashMap<>();
        Map<Line, Long> drafts = new IdentityHashMap<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            Line line = parse(sheetId, raw);
            LocalDate on = line.date() != null ? line.date() : claimDate;
            List<Claim> matches = before
                    .computeIfAbsent(line.person().getId(), id -> claims.forPerson(sheetId, id)).stream()
                    .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                    .filter(c -> on.equals(c.getDate()) && line.note().equals(c.getNote()))
                    .toList();
            if (!matches.isEmpty()) {
                Claim draft = matches.stream().filter(c -> c.getStatus() == ClaimStatus.DRAFT).findFirst()
                        .orElseThrow(() -> refused(raw, "it matches a claim of " + line.person().getName()
                                + " that is no longer a DRAFT"));
                drafts.put(line, draft.getId());
            }
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
            Long id = drafts.get(line);
            if (id == null) {
                id = claims.addClaim(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note(),
                        null, line.ref(), null).getId();
            } else if (claims.claim(sheetId, id).getAmount().compareTo(line.amount()) != 0) {
                claims.editAmount(sheetId, user, id, line.amount());
            }
            claims.move(sheetId, user, id, ClaimStatus.OPEN);
        }
        return lines.size();
    }

    private Line parse(String sheetId, String raw) {
        String[] f = raw.split(",", 4);
        if (f.length < 2) throw refused(raw, "give a name and an amount");
        String name = f[0].trim();
        Person person = name.isEmpty() ? null
                : people.findBySheetIdAndNameOrAlias(sheetId, name).stream().findFirst().orElse(null);
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
