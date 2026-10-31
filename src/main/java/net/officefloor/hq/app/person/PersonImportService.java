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

/** The import box: a person per pasted line, added or updated as the person form would. */
@Service
public class PersonImportService {

    /** The longest name or contact the person form takes. */
    private static final int TEXT_MAX = 200;

    /** The grades the person form takes. */
    private static final Pattern GRADE = Pattern.compile("[ABC]");

    /** Terms the person form takes: a whole number of days from 0 to 36500. */
    private static final Pattern TERMS = Pattern.compile("\\d{1,5}");

    private static final int TERMS_MAX = 36500;

    private final PersonService people;
    private final PersonRepository store;

    public PersonImportService(PersonService people, PersonRepository store) {
        this.people = people;
        this.store = store;
    }

    /** A pasted line read into a person's values. */
    private record Line(String name, String grade, Integer terms, String contact) {
    }

    /** A checked line: the person on the sheet it updates, or null when it adds one. */
    private record Step(Line line, Person match) {
    }

    /** What an import did: the people it added and those it updated, and all of them in line order. */
    public record Result(int added, int updated, List<Person> people) {
    }

    /**
     * Take a person per line of {@code text} (name,grade,terms,contact; the terms and contact may be absent or blank),
     * in order. A line whose name is a person's name or alias on the sheet (ignoring case) updates that person as the
     * person form would: their grade recorded from the sheet's today, and their terms and contact set when the line
     * gives them. Any other line adds a person as the person form adds them. Every line is checked first by the
     * person form's rules, and a name (ignoring case), person or contact repeated within the paste is refused too:
     * when any line is refused, nothing is imported and nothing is recorded. Blank lines are skipped; a paste with no
     * lines is refused.
     */
    @Transactional
    public Result importPeople(String sheetId, String user, String text) {
        Set<String> used = people.usedInTree(sheetId);
        Set<String> names = new HashSet<>();
        Set<Long> matched = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            if (raw.isBlank()) {
                continue;
            }
            Line line = read(raw);
            String lowerName = line.name().toLowerCase(Locale.ROOT);
            Person match = store.findBySheetIdAndNameOrAliasIgnoreCase(sheetId, line.name()).orElse(null);
            if (!names.add(lowerName) || used.contains(lowerName) || (match != null && !matched.add(match.getId()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The name " + line.name() + " is already in use");
            }
            if (line.contact() != null && (!contacts.add(line.contact()) || (match != null
                    ? store.existsBySheetIdAndContactAndIdNot(sheetId, line.contact(), match.getId())
                    : store.existsBySheetIdAndContact(sheetId, line.contact())))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The contact " + line.contact() + " is already in use");
            }
            steps.add(new Step(line, match));
        }
        if (steps.isEmpty()) {
            throw refused("Paste at least one line");
        }
        List<Person> done = new ArrayList<>();
        int added = 0;
        for (Step step : steps) {
            Line line = step.line();
            Person match = step.match();
            if (match == null) {
                done.add(people.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact()));
                added++;
            } else {
                done.add(people.update(sheetId, user, match.getId(), match.getVersion(), match.getName(), line.grade(),
                        null, line.terms() != null ? line.terms() : match.getTerms(),
                        line.contact() != null ? line.contact() : match.getContact()));
            }
        }
        return new Result(added, done.size() - added, done);
    }

    private static Line read(String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length < 2 || fields.length > 4) {
            throw refused("Each line is name,grade,terms,contact");
        }
        String name = fields[0].trim();
        if (name.isEmpty() || name.length() > TEXT_MAX) {
            throw refused("Each line needs a name of at most " + TEXT_MAX + " characters");
        }
        String grade = fields[1].trim();
        if (!GRADE.matcher(grade).matches()) {
            throw refused("A grade is A, B or C");
        }
        Integer terms = null;
        String termsText = fields.length > 2 ? fields[2].trim() : "";
        if (!termsText.isEmpty()) {
            if (!TERMS.matcher(termsText).matches() || Integer.parseInt(termsText) > TERMS_MAX) {
                throw refused("Terms are a whole number of days from 0 to " + TERMS_MAX);
            }
            terms = Integer.valueOf(termsText);
        }
        String contact = fields.length > 3 ? fields[3] : "";
        if (contact.length() > TEXT_MAX) {
            throw refused("A contact is at most " + TEXT_MAX + " characters");
        }
        contact = contact.isBlank() ? null : contact.trim().toLowerCase(Locale.ROOT);
        return new Line(name, grade, terms, contact);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
