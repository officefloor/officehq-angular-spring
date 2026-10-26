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
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasteClaimsService {

    /** A pasted line, read: the person it names and the claim's fields (no date: the sheet's entry date,
     *  else its today; no ref: no reference). */
    private record Line(Person person, BigDecimal amount, LocalDate date, String note, String ref) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;

    public PasteClaimsService(PersonRepository people, ClaimRepository claims, ClaimService claimService) {
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
    }

    /** Add and open a claim per pasted line of name,amount,date,note, in order. Every line is read first,
     *  then each is added with the claim form's rules and opened by the user as the open button would;
     *  one change: when any line is refused, nothing is added and nothing is written. A line whose person,
     *  date and note match one of their claims as it stood before the paste updates that claim instead: a
     *  DRAFT is edited to the line's amount (when it differs) and opened; a claim in any other status is
     *  refused. Blank lines are skipped; a paste with no lines is refused, as is one giving a reference
     *  already used on the sheet or by an earlier line, compared without regard to case. Returns the
     *  lines' claims, in the order of their lines. */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        List<Claim> matches = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        List<Claim> existing = claims.findBySheetIdOrderByListedDesc(sheetId);
        LocalDate undated = claimService.entryDateOr(sheetId);
        int number = 0;
        for (String raw : text.split("\\r?\\n")) {
            number++;
            if (raw.isBlank()) continue;
            Line line = read(sheetId, raw, number);
            if (line.ref() != null && (!refs.add(line.ref().toLowerCase(Locale.ROOT))
                    || claims.refUsed(sheetId, line.ref(), null))) {
                throw refused(number, "the reference '" + line.ref() + "' is already used");
            }
            Claim match = match(existing, line, line.date() != null ? line.date() : undated);
            if (match != null && match.getStatus() != ClaimStatus.DRAFT) {
                throw refused(number, "it matches a " + match.getStatus() + " claim, which cannot be updated");
            }
            lines.add(line);
            matches.add(match);
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Paste at least one line.");
        }
        List<Claim> done = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            Claim claim = matches.get(i);
            if (claim == null) {
                claim = claimService.add(sheetId, user, line.person().getId(), line.amount(), line.date(),
                        line.note(), null, line.ref(), null);
            } else if (claim.getAmount().compareTo(line.amount()) != 0) {
                claim = claimService.update(sheetId, user, claim.getId(), line.amount(), claim.getDate(),
                        claim.getNote(), claim.getCorrects(), line.ref() != null ? line.ref() : claim.getRef(),
                        String.join(",", claim.getTags()));
            }
            done.add(claimService.changeStatus(sheetId, user, claim.getId(), ClaimStatus.OPEN));
        }
        return done;
    }

    /** The person's claim, as it stood before the paste, dated the date with the line's note; null when none. */
    private static Claim match(List<Claim> existing, Line line, LocalDate date) {
        return existing.stream()
                .filter((c) -> c.getPersonId().equals(line.person().getId()) && date.equals(c.getDate())
                        && line.note().equals(c.getNote()))
                .findFirst().orElse(null);
    }

    /** A line of name,amount,date,note; the note may hold commas, the date may be blank and the note absent.
     *  A note ending in ' #<reference>' gives the claim that reference, which is not part of the note. */
    private Line read(String sheetId, String raw, int number) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 2) throw refused(number, "it needs at least a name and an amount");
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null
                : people.findByNameOrAlias(sheetId, Person.keyOf(name)).orElse(null);
        if (person == null) throw refused(number, "no one on the sheet is named '" + name + "'");
        BigDecimal amount;
        try {
            amount = new BigDecimal(fields[1].trim());
        } catch (NumberFormatException e) {
            throw refused(number, "the amount is not a number");
        }
        if (amount.scale() > 2 || amount.precision() - amount.scale() > 10) {
            throw refused(number, "the amount has more than two decimals or ten digits");
        }
        LocalDate date = null;
        if (fields.length > 2 && !fields[2].isBlank()) {
            try {
                date = LocalDate.parse(fields[2].trim());
            } catch (DateTimeParseException e) {
                throw refused(number, "the date is not a date (yyyy-mm-dd)");
            }
        }
        String note = fields.length > 3 ? fields[3].trim() : "";
        String ref = null;
        int hash = note.lastIndexOf(" #");
        if (hash >= 0) {
            ref = ClaimService.refOf(note.substring(hash + 2));
            note = note.substring(0, hash).trim();
        }
        if (ref != null && ref.length() > 220) throw refused(number, "the reference is longer than 220 characters");
        if (note.length() > 1000) throw refused(number, "the note is longer than 1000 characters");
        return new Line(person, amount, date, note, ref);
    }

    private static ResponseStatusException refused(int number, String why) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Line " + number + ": " + why + ".");
    }
}
