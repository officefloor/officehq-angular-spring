package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The people kept on a sheet, in the order they were added. A name is stored without surrounding
 * spaces and is unique on its sheet regardless of case.
 */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetService sheets, Audit audit) {
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        sheets.load(sheetId, user);
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)) {
            throw nameInUse();
        }
        Person person = people.save(new Person(sheetId, trimmed, grade));
        audit.change("PERSON_ADDED", sheetId, user, "name=" + person.getName(), "grade=" + person.getGrade());
        return person;
    }

    @Transactional
    public Person update(String sheetId, String user, Long personId, String name, String grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, trimmed, personId)) {
            throw nameInUse();
        }
        person.setName(trimmed);
        person.setGrade(grade);
        audit.change("PERSON_EDITED", sheetId, user, "name=" + person.getName(), "grade=" + person.getGrade());
        return person;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use");
    }
}
