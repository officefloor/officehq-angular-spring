package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonService {

    /** A person's statements: how many were taken, and the last one (null: none). */
    public record Statements(int count, Statement last) {
        public static final Statements NONE = new Statements(0, null);
    }

    private final PersonRepository people;
    private final StatementRepository statements;
    private final SheetService sheets;
    private final RollupService rollups;
    private final Audit audit;

    public PersonService(PersonRepository people, StatementRepository statements, SheetService sheets,
            RollupService rollups, Audit audit) {
        this.people = people;
        this.statements = statements;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's people, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Each person's statements on the sheet, by person id; a person with none is absent. */
    @Transactional(readOnly = true)
    public Map<Long, Statements> statements(String sheetId) {
        Map<Long, Statements> out = new HashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            out.merge(s.getPersonId(), new Statements(1, s), (was, now) -> new Statements(was.count() + 1, s));
        }
        return out;
    }

    /** Add a person (the sheet is started when it was never loaded before). */
    @Transactional
    public Person add(String sheetId, String user, String name, Grade grade) {
        sheets.load(sheetId, user);
        String trimmed = name.trim();
        requireUnusedName(sheetId, null, trimmed);
        RollupService.Rollup before = rollups.snapshot(sheetId);
        Person saved;
        try {
            saved = people.saveAndFlush(new Person(sheetId, trimmed, grade));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit(sheetId, user, "PERSON_ADDED", saved);
        rollups.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Change a person's name and grade, from the version of them the editor loaded: refused when
     * someone else has saved them since.
     */
    @Transactional
    public Person edit(String sheetId, String user, long personId, long version, String name, Grade grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (person.getVersion() != version) throw editedMeanwhile();
        String trimmed = name.trim();
        requireUnusedName(sheetId, personId, trimmed);
        RollupService.Rollup before = rollups.snapshot(sheetId);
        person.setName(trimmed);
        person.setGrade(grade);
        Person saved;
        try {
            saved = people.saveAndFlush(person);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw editedMeanwhile();
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit(sheetId, user, "PERSON_EDITED", saved);
        rollups.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Retire a person from a date (the sheet's today when none is given). */
    @Transactional
    public Person retire(String sheetId, String user, long personId, LocalDate from) {
        sheets.lock(sheetId, user);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate dated = from != null ? from : sheets.today(sheetId);
        person.setRetiredFrom(dated);
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " from=" + dated);
        return saved;
    }

    private void audit(String sheetId, String user, String event, Person person) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " grade=" + person.getGrade());
    }

    /** Refuse a name another person on the sheet already has, ignoring case. */
    private void requireUnusedName(String sheetId, Long personId, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        boolean used = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .anyMatch(p -> !p.getId().equals(personId) && p.getName().toLowerCase(Locale.ROOT).equals(key));
        if (used) throw nameInUse();
    }

    private static ResponseStatusException editedMeanwhile() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Someone else has saved this person since");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use");
    }
}
