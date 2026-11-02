package net.officefloor.hq.app.watch;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people each user watches on a sheet, and the notices sent them. */
@Service
public class WatchService {

    private final WatchRepository watches;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public WatchService(WatchRepository watches, PersonRepository people, SheetService sheets, Audit audit) {
        this.watches = watches;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The people the user watches on the sheet, in the order they started watching. */
    @Transactional(readOnly = true)
    public List<Watch> list(String sheetId, String user) {
        return watches.findBySheetIdAndUserOrderByIdAsc(sheetId, user);
    }

    /** Start watching a person; refused when the user already watches them. */
    @Transactional
    public Watch watch(String sheetId, String user, long personId) {
        Person person = person(sheetId, personId);
        if (watches.findByPersonIdAndUser(person.getId(), user).isPresent()) {
            throw alreadyWatching();
        }
        Watch saved;
        try {
            saved = watches.saveAndFlush(new Watch(sheetId, person.getId(), user, sheets.today(sheetId)));
        } catch (DataIntegrityViolationException e) {
            throw alreadyWatching();
        }
        audit.recordOnCommit("PERSON_WATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        return saved;
    }

    /** Stop watching a person; refused when the user does not watch them. */
    @Transactional
    public void unwatch(String sheetId, String user, long personId) {
        Person person = person(sheetId, personId);
        Watch watch = watches.findByPersonIdAndUser(person.getId(), user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "You do not watch this person"));
        watches.delete(watch);
        watches.flush();
        audit.recordOnCommit("PERSON_UNWATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
    }

    /** Tell each user watching the person, in the order they started watching, that a claim of
     *  theirs was settled. Recorded once the change commits, after the records made before it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyWatchers(String sheetId, String user, Person person, String number) {
        for (Watch watch : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + sheetId + " by=" + user + " user=" + watch.getUser()
                    + " person=" + person.getName() + " number=" + number);
        }
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
    }

    private static ResponseStatusException alreadyWatching() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "You already watch this person");
    }
}
