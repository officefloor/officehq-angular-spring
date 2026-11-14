package net.officefloor.hq.app.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;

/**
 * Reads the audit file back for the viewer: it is not a second record, only what the file holds.
 */
@Service
public class AuditViewService {

    /** The start of a field: a space, then its key and '='. A field's value runs to the next field. */
    private static final Pattern FIELD = Pattern.compile(" ([a-z][a-z0-9-]*)=");

    private final Audit audit;
    private final PersonRepository people;
    private final SheetService sheets;
    private final ClaimRepository claims;

    public AuditViewService(Audit audit, PersonRepository people, SheetService sheets, ClaimRepository claims) {
        this.audit = audit;
        this.people = people;
        this.sheets = sheets;
        this.claims = claims;
    }

    /**
     * A claim's timeline: the sheet's lines about it, newest first, as the file holds them. For a
     * numbered claim, the lines whose number= is its number; for a claim with no number, the lines
     * with no number whose person=, amount= and date= are its person's name, its amount and its date.
     */
    public List<String> claimLines(String sheetId, Long claimId) {
        Claim claim = claims.findById(claimId).filter(c -> c.getSheetId().equals(sheetId))
                .orElseThrow(NoSuchElementException::new);
        String number = claim.getNumber();
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse(null);
        String amount = claim.getAmount().toPlainString();
        String date = claim.getDate().toString();
        List<String> found = new ArrayList<>();
        for (String line : audit.lines()) {
            if (!field(line, "sheet").filter(sheetId::equals).isPresent()) {
                continue;
            }
            Optional<String> lineNumber = field(line, "number");
            boolean about = number != null
                    ? lineNumber.filter(number::equals).isPresent()
                    : lineNumber.isEmpty() && person != null
                            && field(line, "person").filter(person::equals).isPresent()
                            && field(line, "amount").filter(amount::equals).isPresent()
                            && field(line, "date").filter(date::equals).isPresent();
            if (about) {
                found.add(line);
            }
        }
        Collections.reverse(found);
        return found;
    }

    /**
     * The sheet's lines, newest first. With a non-blank name, only the lines whose name= or person=
     * field is that name, compared as names are (case and surrounding spaces ignored), followed by
     * the matching lines of the sheet's descendants (each sheet's newest first, sheets by id); when
     * that name is a person transferred to the sheet, followed by the lines about them on the sheet
     * they came from, newest first.
     */
    public List<String> lines(String sheetId, String name) {
        String wanted = name == null ? "" : normal(name);
        List<String> found = sheetLines(sheetId, wanted);
        if (!wanted.isEmpty()) {
            sheets.descendants(sheetId).stream().sorted()
                    .forEach(descendant -> found.addAll(sheetLines(descendant, wanted)));
            people.findByName(sheetId, name.trim()).filter(person -> person.getOriginSheetId() != null)
                    .ifPresent(person -> found.addAll(sheetLines(person.getOriginSheetId(), normal(person.getName()))));
        }
        return found;
    }

    /** The sheet's lines, newest first; with a non-blank wanted name, only those about that person. */
    private List<String> sheetLines(String sheetId, String wanted) {
        List<String> found = new ArrayList<>();
        for (String line : audit.lines()) {
            if (!field(line, "sheet").filter(sheetId::equals).isPresent()) {
                continue;
            }
            if (!wanted.isEmpty() && !about(line, wanted)) {
                continue;
            }
            found.add(line);
        }
        Collections.reverse(found);
        return found;
    }

    private static boolean about(String line, String wanted) {
        return field(line, "name").map(AuditViewService::normal).filter(wanted::equals).isPresent()
                || field(line, "person").map(AuditViewService::normal).filter(wanted::equals).isPresent();
    }

    /** The value of the line's first field with this key, up to the next field. */
    private static Optional<String> field(String line, String key) {
        Matcher m = FIELD.matcher(line);
        while (m.find()) {
            if (!m.group(1).equals(key)) {
                continue;
            }
            int start = m.end();
            int end = m.find() ? m.start() : line.length();
            return Optional.of(line.substring(start, end));
        }
        return Optional.empty();
    }

    private static String normal(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
