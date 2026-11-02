package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people kept on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final Rollup rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetService sheets, Rollup rollup, Audit audit) {
        this.people = people;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The people on the sheet in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a person to the sheet. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        sheets.load(sheetId, user);
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, name)) {
            throw nameInUse();
        }
        return rollup.tracking(sheetId, user, () -> {
            Person fresh = new Person(sheetId, name, grade);
            fresh.setTerms(terms);
            Person person = saveUnique(fresh);
            audit.recordOnCommit(record("PERSON_ADDED", sheetId, user, person, sheets.today(sheetId))
                    + (terms != null ? " terms=" + terms : ""));
            return person;
        });
    }

    /** Change a person's name, and record their grade from a day (null: the sheet's today), keeping
     *  the grades already recorded; a grade left as the one in force on the sheet's today, with no
     *  day given, records nothing; and set their own terms (null: none). Refused when the person is no longer at the version the editor
     *  loaded: another user saved them in the meantime. */
    @Transactional
    public Person edit(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (person.getVersion() != version) {
            throw changedMeanwhile();
        }
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, name, personId)) {
            throw nameInUse();
        }
        return rollup.tracking(sheetId, user, () -> {
            LocalDate today = sheets.today(sheetId);
            Integer termsBefore = person.getTerms();
            person.setName(name);
            person.setTerms(terms);
            if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
                person.setGradeFrom(grade, gradeFrom != null ? gradeFrom : today);
            }
            Person saved = saveUnique(person);
            audit.recordOnCommit(record("PERSON_EDITED", sheetId, user, saved, today)
                    + (Objects.equals(termsBefore, terms) ? "" : " terms=" + (terms != null ? terms : "")));
            return saved;
        });
    }

    /** Retire a person from a day; a day left out takes the sheet's today. */
    @Transactional
    public Person retire(String sheetId, String user, long personId, LocalDate from) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        return rollup.tracking(sheetId, user, () -> {
            person.setRetiredFrom(on);
            Person saved = saveUnique(person);
            audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                    + " from=" + on);
            return saved;
        });
    }

    /** Put a person on hold from a day (null: the sheet's today) to a day (null: open-ended),
     *  keeping their other holds; refused when the to-date is before the from-date. */
    @Transactional
    public Person hold(String sheetId, String user, long personId, LocalDate from, LocalDate to) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(on)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts");
        }
        return rollup.tracking(sheetId, user, () -> {
            person.addHold(on, to);
            Person saved = saveUnique(person);
            audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                    + " from=" + on);
            return saved;
        });
    }

    /** A record of the person with the grade in force on the sheet's today. */
    private static String record(String event, String sheetId, String user, Person person, LocalDate today) {
        return event + " sheet=" + sheetId + " by=" + user + " name=" + person.getName() + " grade="
                + person.gradeOn(today);
    }

    /** Save, refusing a name another user took on the sheet in the meantime, or a person another
     *  user saved in the meantime. */
    private Person saveUnique(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        } catch (OptimisticLockingFailureException e) {
            throw changedMeanwhile();
        }
    }

    private static ResponseStatusException changedMeanwhile() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else saved this person in the meantime");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet");
    }
}
