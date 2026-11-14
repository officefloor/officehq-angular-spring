package net.officefloor.hq.app.person;

import java.util.List;
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

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a person to the sheet (the sheet is created first if it was never loaded). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        sheets.load(sheetId, user);
        String trimmed = name.trim();
        requireUnusedName(sheetId, null, trimmed);
        try {
            return people.saveAndFlush(new Person(sheetId, trimmed, grade));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse(); // added by another user at the same moment
        }
    }

    @Transactional
    public Person update(String sheetId, long id, String name, String grade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        String trimmed = name.trim();
        requireUnusedName(sheetId, id, trimmed);
        person.setName(trimmed);
        person.setGrade(grade);
        try {
            people.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameInUse(); // taken by another user at the same moment
        }
        return person;
    }

    /** Refuse a name another person on the sheet already has (ignoring case). */
    private void requireUnusedName(String sheetId, Long exceptId, String name) {
        boolean taken = people.findBySheetIdAndNameIgnoreCase(sheetId, name).stream()
                .anyMatch(p -> !p.getId().equals(exceptId));
        if (taken) {
            throw nameInUse();
        }
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use");
    }
}
