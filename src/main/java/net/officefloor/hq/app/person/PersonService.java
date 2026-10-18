package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Locale;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;

    public PersonService(PersonRepository people, SheetService sheets) {
        this.people = people;
        this.sheets = sheets;
    }

    /** The sheet's people, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a person (the sheet is started when it was never loaded before). */
    @Transactional
    public Person add(String sheetId, String user, String name, Grade grade) {
        sheets.load(sheetId, user);
        String trimmed = name.trim();
        requireUnusedName(sheetId, null, trimmed);
        try {
            return people.saveAndFlush(new Person(sheetId, trimmed, grade));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
    }

    /** Change a person's name and grade. */
    @Transactional
    public Person edit(String sheetId, long personId, String name, Grade grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        String trimmed = name.trim();
        requireUnusedName(sheetId, personId, trimmed);
        person.setName(trimmed);
        person.setGrade(grade);
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
    }

    /** Refuse a name another person on the sheet already has, ignoring case. */
    private void requireUnusedName(String sheetId, Long personId, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        boolean used = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .anyMatch(p -> !p.getId().equals(personId) && p.getName().toLowerCase(Locale.ROOT).equals(key));
        if (used) throw nameInUse();
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use");
    }
}
