package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportPeopleService {

    /** The grades the person form takes. */
    private static final Pattern GRADE = Pattern.compile("[ABC]");

    /** Terms as the person form takes them: whole days, blank for none. */
    private static final Pattern TERMS = Pattern.compile("\\d{0,4}");

    /** The longest name or contact the person form takes. */
    private static final int MAX = 200;

    private final PersonService people;
    private final PersonRepository repository;

    public ImportPeopleService(PersonService people, PersonRepository repository) {
        this.people = people;
        this.repository = repository;
    }

    /** One pasted line as the person form would submit it, with the person on the sheet it names
     *  (by name or alias), null when none. */
    private record Line(String name, String grade, Integer terms, String contact, Person existing) {
    }

    /** The people an import added and those it updated, each in the order of their lines. */
    public record Imported(List<Person> added, List<Person> updated) {
    }

    /** Add or update a person for each line of name,grade,terms,contact, in order: the terms and
     *  contact may be absent or blank; blank lines are skipped. Every line is checked first by the
     *  person form's rules (400 when it is not a person the form takes, 409 when a name, person or
     *  contact is repeated in the paste). A line naming a person on the sheet (by name or alias, as
     *  names are compared) saves them as the form would: their grade recorded from the sheet's
     *  today, their terms and contact set when the line gives them, else kept. Every other line is
     *  added as the form adds them; one change, so when any line is refused (else as the form
     *  refuses it) nothing is imported. */
    @Transactional
    public Imported importPeople(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        Set<Long> matched = new HashSet<>();
        int n = 0;
        for (String raw : text.split("\\r?\\n|\\r")) {
            n++;
            if (raw.isBlank()) continue;
            String[] f = raw.split(",", -1);
            if (f.length < 2 || f.length > 4) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": give name,grade,terms,contact");
            }
            String name = f[0].trim();
            String grade = f[1].trim();
            String terms = f.length > 2 ? f[2].trim() : "";
            String contact = f.length > 3 ? f[3].trim() : "";
            if (name.isEmpty() || name.length() > MAX || !GRADE.matcher(grade).matches()
                    || !TERMS.matcher(terms).matches() || contact.length() > MAX) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": not a person the form takes");
            }
            String contactValue = contact.isEmpty() ? null : contact.toLowerCase(Locale.ROOT);
            if (!names.add(name.toLowerCase(Locale.ROOT)) || (contactValue != null && !contacts.add(contactValue))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Line " + n + ": a name or contact given twice");
            }
            Person existing = repository.findBySheetIdAndNameOrAlias(sheetId, name).orElse(null);
            if (existing != null && !matched.add(existing.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Line " + n + ": a person given twice");
            }
            lines.add(new Line(name, grade, terms.isEmpty() ? null : Integer.valueOf(terms), contactValue, existing));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        List<Person> added = new ArrayList<>();
        List<Person> updated = new ArrayList<>();
        for (Line line : lines) {
            Person p = line.existing();
            if (p == null) {
                added.add(people.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact()));
            } else {
                updated.add(people.update(sheetId, user, p.getId(), p.getVersion(), p.getName(), line.grade(), null,
                        line.terms() != null ? line.terms() : p.getTerms(),
                        line.contact() != null ? line.contact() : p.getContact()));
            }
        }
        return new Imported(added, updated);
    }
}
