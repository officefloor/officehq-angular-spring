package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
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

    /** A claim as it stood before the paste: what a line is matched against. */
    private record Before(long id, long personId, LocalDate date, String note, ClaimStatus status, long listed) {
    }

    private final PersonRepository people;
    private final ClaimService claims;
    private final ClaimRepository stored;
    private final SheetService sheets;

    public PasteClaimsService(PersonRepository people, ClaimService claims, ClaimRepository stored, SheetService sheets) {
        this.people = people;
        this.claims = claims;
        this.stored = stored;
        this.sheets = sheets;
    }

    /** Take each line of name,amount,date,note, in order, into an OPEN claim of name,amount,date,note, in order: the name of a person on
     *  the sheet (ignoring case and surrounding spaces), an amount, a date (blank: the sheet's
     *  entry date while set, else its today) and a note that may hold commas or be absent, ending
     *  in ` #<reference>` to give the claim that reference (not part of the note); blank lines are skipped. Each line is
     *  taken by the claim form's rules. A line whose person, date and note match one of that
     *  person's claims as they stood before the paste (void and deleted claims never match) updates
     *  the newest matching DRAFT to the line's amount (as its editor would, when that changes)
     *  instead of adding one; a line whose matches include no DRAFT is refused (409). Each claim is
     *  then opened by the user as the open button would, in line order. One change, so when any line
     *  is refused (400 when it is not a claim the form takes, 422 when its name is not on the sheet,
     *  else as the claim form, its editor or the open button refuses it) nothing is changed. The
     *  claims, in line order. */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<String[]> lines = new ArrayList<>();
        for (String line : text.split("\\r?\\n|\\r")) {
            if (!line.isBlank()) lines.add(line.split(",", 4));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        List<Before> before = stored.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .map(c -> new Before(c.getId(), c.getPersonId(), c.getDate(), c.getNote(), c.getStatus(), c.getListed()))
                .toList();
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
            String ref = null;
            int hash = note.lastIndexOf(" #");
            if (hash >= 0 && !note.substring(hash + 2).isBlank()) {
                ref = note.substring(hash + 2).trim();
                note = note.substring(0, hash).trim();
            }
            if (name.isEmpty() || !AMOUNT.matcher(amount).matches() || note.length() > NOTE_MAX) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": not a claim the form takes");
            }
            LocalDate on;
            try {
                on = date.isEmpty() ? null : LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": the date is not yyyy-mm-dd");
            }
            Person person = people.findBySheetIdAndNameOrAlias(sheetId, name)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Line " + n + ": no person named " + name));
            BigDecimal sum = new BigDecimal(amount);
            LocalDate day = on != null ? on : sheets.entryDate(sheetId);
            String said = note;
            List<Before> matches = before.stream()
                    .filter(c -> c.personId() == person.getId() && day.equals(c.date()) && said.equals(c.note()))
                    .toList();
            Claim claim;
            if (matches.isEmpty()) {
                claim = claims.add(sheetId, user, person.getId(), sum, on, note, null, ref, null);
            } else {
                Before draft = matches.stream().filter(c -> c.status() == ClaimStatus.DRAFT)
                        .max(Comparator.comparingLong(Before::listed))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                                "Line " + n + ": it matches a claim that is no longer a DRAFT"));
                claim = stored.findByIdAndSheetId(draft.id(), sheetId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
                boolean reRef = ref != null && !ref.equalsIgnoreCase(String.valueOf(claim.getRef()));
                if (claim.getAmount().compareTo(sum) != 0 || reRef) {
                    claim = claims.update(sheetId, user, claim.getId(), sum, claim.getDate(), claim.getNote(),
                            claim.getCorrects(), reRef ? ref : claim.getRef(), claim.getTags());
                }
            }
            added.add(claims.changeStatus(sheetId, user, claim.getId(), ClaimStatus.OPEN));
        }
        return added;
    }
}
