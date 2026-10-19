package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;

    public PersonService(PersonRepository people, SheetRepository sheets) {
        this.people = people;
        this.sheets = sheets;
    }

    /** The sheet's people in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        requireSheet(sheetId);
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String name, String grade) {
        requireSheet(sheetId);
        return people.save(new Person(sheetId, name.trim(), grade));
    }

    @Transactional
    public Person update(String sheetId, Long id, String name, String grade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        person.setName(name.trim());
        person.setGrade(grade);
        return person;
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet.");
        }
    }
}
