package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The people on a sheet: listed in the order they were added, added and edited one at a time. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;

    public PersonService(PersonRepository people, SheetRepository sheets) {
        this.people = people;
        this.sheets = sheets;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String name, String grade) {
        if (!sheets.existsById(sheetId)) throw new SheetNotFoundException();
        return people.save(new Person(sheetId, name, grade));
    }

    @Transactional
    public Person edit(String sheetId, Long id, String name, String grade) {
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        person.setName(name);
        person.setGrade(grade);
        return person;
    }
}
