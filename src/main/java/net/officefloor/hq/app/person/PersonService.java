package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    private final PersonRepository people;
    private final PersonStatementRepository statements;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, PersonStatementRepository statements, SheetService sheets,
            RollupService rollup, Audit audit) {
        this.people = people;
        this.statements = statements;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional(readOnly = true)
    public Person get(String sheetId, long id) {
        return people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
    }

    /** Each person's statement summary, by person id; a person with none is missing. */
    @Transactional(readOnly = true)
    public Map<Long, StatementSummary> statementSummaries(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PersonStatement::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), StatementSummary::of)));
    }

    @Transactional(readOnly = true)
    public StatementSummary statementSummary(long personId) {
        return StatementSummary.of(statements.findByPersonIdOrderByIdAsc(personId));
    }

    /** Add a person to the sheet (the sheet is created first if it was never loaded). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        sheets.load(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.trim();
        requireUnusedName(sheetId, null, trimmed);
        Person person;
        try {
            person = people.saveAndFlush(new Person(sheetId, trimmed, grade));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse(); // added by another user at the same moment
        }
        record("PERSON_ADDED", sheetId, user, person);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Save a person; refused when {@code version} (the one the editor loaded) is no longer the stored one. */
    @Transactional
    public Person update(String sheetId, String user, long id, Long version, String name, String grade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (version == null || version != person.getVersion()) {
            throw savedByAnother();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.trim();
        requireUnusedName(sheetId, id, trimmed);
        person.setName(trimmed);
        person.setGrade(grade);
        try {
            people.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameInUse(); // taken by another user at the same moment
        } catch (ObjectOptimisticLockingFailureException e) {
            throw savedByAnother(); // saved by another user at the same moment
        }
        record("PERSON_EDITED", sheetId, user, person);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Retire a person from a date (a missing one takes the sheet's today); they count as retired
     *  at once. Retiring them again moves the date. */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate from) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        person.setRetiredFrom(on);
        people.flush();
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " from=" + on);
        return person;
    }

    private void record(String event, String sheetId, String user, Person person) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " grade=" + person.getGrade());
    }

    /** Refuse a name another person on the sheet already has (ignoring case). */
    private void requireUnusedName(String sheetId, Long exceptId, String name) {
        boolean taken = people.findBySheetIdAndNameIgnoreCase(sheetId, name).stream()
                .anyMatch(p -> !p.getId().equals(exceptId));
        if (taken) {
            throw nameInUse();
        }
    }

    private static ResponseStatusException savedByAnother() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Saved by someone else since it was loaded");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use");
    }
}
