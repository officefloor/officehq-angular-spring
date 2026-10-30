package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Import people: lines of {@code name,grade,terms,contact} (terms and contact may be left out or blank; a blank
 * grade is the sheet's default grade, A when none is set). A line
 * whose name is the name or an alias of a person on the sheet (compared as names are) updates that person as
 * the person form edits them: their grade is recorded from the sheet's today, and their terms and contact are
 * set when the line gives them. Any other line adds a person as the person form adds them. Every line is first
 * held to the person form's rules: a new name in use as a name or alias on the sheet's tree, a contact another
 * person on the sheet has, or a name, person or contact given twice in the paste is refused, and the whole
 * import is one change: a refused line imports nothing.
 */
@Service
public class PersonImportService {

    private static final Pattern GRADE = Pattern.compile("[ABC]");
    private static final Pattern TERMS = Pattern.compile("\\d{1,5}");
    private static final int NAME_MAX = 200;
    private static final int CONTACT_MAX = 200;

    private record Line(String name, String grade, Integer terms, String contact) {
    }

    /** A line to import, with the person on the sheet it updates (none: it adds one). */
    private record Planned(Line line, Person person) {
    }

    /** How many people an import added and how many it updated. */
    public record Result(int added, int updated) {
    }

    private final PersonService service;
    private final PersonRepository people;
    private final SheetRepository sheetRows;
    private final SheetService sheets;

    public PersonImportService(PersonService service, PersonRepository people, SheetRepository sheetRows,
            SheetService sheets) {
        this.service = service;
        this.people = people;
        this.sheetRows = sheetRows;
        this.sheets = sheets;
    }

    /** Update or add a person per line, in order; returns how many were added and updated. */
    @Transactional
    public Result importPeople(String sheetId, String user, String text) {
        sheetRows.lockById(sheetId);
        List<Planned> planned = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<Long> updated = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        String defaultGrade = sheets.settings(sheetId).defaultGrade();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            Line line = parse(raw, defaultGrade);
            Optional<Person> match = service.findByNameOrAlias(sheetId, line.name());
            if (match.isEmpty() && service.inUse(sheetId, line.name(), null)) {
                throw refused(raw, "the name \"" + line.name() + "\" is already in use");
            }
            if (!names.add(line.name().toLowerCase(Locale.ROOT))
                    || match.isPresent() && !updated.add(match.get().getId())) {
                throw refused(raw, "the name \"" + line.name() + "\" is given twice in the paste");
            }
            if (line.contact() != null) {
                boolean taken = match.isPresent()
                        ? people.existsBySheetIdAndContactAndIdNot(sheetId, line.contact(), match.get().getId())
                        : people.existsBySheetIdAndContact(sheetId, line.contact());
                if (taken) {
                    throw refused(raw, "the contact \"" + line.contact() + "\" is already in use");
                }
                if (!contacts.add(line.contact())) {
                    throw refused(raw, "the contact \"" + line.contact() + "\" is given twice in the paste");
                }
            }
            planned.add(new Planned(line, match.orElse(null)));
        }
        if (planned.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Paste at least one line");
        }
        int added = 0;
        for (Planned p : planned) {
            Line line = p.line();
            if (p.person() == null) {
                service.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact());
                added++;
            } else {
                Person person = p.person();
                service.edit(sheetId, user, person.getId(), null, person.getName(), line.grade(), null,
                        line.terms() != null ? line.terms() : person.getTerms(),
                        line.contact() != null ? line.contact() : person.getContact());
            }
        }
        return new Result(added, planned.size() - added);
    }

    private static Line parse(String raw, String defaultGrade) {
        String[] f = raw.split(",", -1);
        if (f.length < 2 || f.length > 4) throw refused(raw, "give a name, a grade, and optionally terms and a contact");
        String name = f[0].trim();
        if (name.isEmpty()) throw refused(raw, "give a name");
        if (name.length() > NAME_MAX) throw refused(raw, "the name is too long");
        String grade = f[1].isBlank() ? defaultGrade : f[1].trim();
        if (!GRADE.matcher(grade).matches()) throw refused(raw, "the grade must be A, B or C");
        Integer terms = null;
        if (f.length > 2 && !f[2].isBlank()) {
            String days = f[2].trim();
            if (!TERMS.matcher(days).matches()) throw refused(raw, "the terms must be a number of days");
            terms = Integer.valueOf(days);
        }
        String contact = null;
        if (f.length > 3 && !f[3].isBlank()) {
            if (f[3].length() > CONTACT_MAX) throw refused(raw, "the contact is too long");
            contact = f[3].trim().toLowerCase(Locale.ROOT);
        }
        return new Line(name, grade, terms, contact);
    }

    private static ResponseStatusException refused(String line, String why) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Line \"" + line + "\": " + why);
    }
}
