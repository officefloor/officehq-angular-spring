package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.sheet.SheetService;
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
        return people.save(new Person(sheetId, name.trim(), grade));
    }

    /** Change a person's name and grade. */
    @Transactional
    public Person edit(String sheetId, long personId, String name, Grade grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        person.setName(name.trim());
        person.setGrade(grade);
        return person;
    }
}
