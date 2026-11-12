package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the people on each sheet. Names arrive validated (not blank) and are stored trimmed; a name
 * is unique on the sheet, compared without regard to case.
 */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;
    private final StatementRepository statements;
    private final Rollup rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetRepository sheets, StatementRepository statements,
            Rollup rollup, Audit audit) {
        this.rollup = rollup;
        this.people = people;
        this.sheets = sheets;
        this.statements = statements;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        requireSheet(sheetId);
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The sheet's statements by person id, each person's in the order they were taken. */
    @Transactional(readOnly = true)
    public Map<Long, List<Statement>> statements(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(Statement::getPersonId));
    }

    /** The person's statements in the order they were taken. */
    @Transactional(readOnly = true)
    public List<Statement> statementsOf(long personId) {
        return statements.findByPersonIdOrderByIdAsc(personId);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        requireSheet(sheetId);
        requireFreeName(sheetId, name.trim(), null);
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = saved(new Person(sheetId, name.trim(), grade));
        audit("PERSON_ADDED", person, user);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    @Transactional
    public Person update(String sheetId, String user, long personId, long version, String name, String grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getVersion() != version) {
            throw new PersonVersionConflictException(person);
        }
        requireFreeName(sheetId, name.trim(), person.getId());
        Rollup.Snapshot before = rollup.take(sheetId);
        person.setName(name.trim());
        person.setGrade(grade);
        Person updated = saved(person);
        audit("PERSON_EDITED", updated, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    private void audit(String event, Person person, String user) {
        audit.recordOnCommit(event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + person.getGrade());
    }

    private void requireFreeName(String sheetId, String name, Long exceptId) {
        if (people.nameTaken(sheetId, name, exceptId)) {
            throw new PersonNameTakenException();
        }
    }

    /** Save now, so a name another user took at the same moment is refused here, not at commit. */
    private Person saved(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw new PersonNameTakenException();
        }
    }

    /** The person as stored now, for an editor whose save was refused. */
    @Transactional(readOnly = true)
    public Person current(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
    }
}
