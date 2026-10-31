package net.officefloor.hq.app.watch;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people each user watches on a sheet, and the notices sent to them. */
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
        return watches.findBySheetIdAndUserNameOrderByIdAsc(sheetId, user);
    }

    /** Start or stop the user watching the person; refused when the user already does (or does not) watch them. */
    @Transactional
    public boolean set(String sheetId, String user, long personId, boolean watched) {
        sheets.load(sheetId, user);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        var existing = watches.findByPersonIdAndUserName(person.getId(), user);
        if (existing.isPresent() == watched) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    watched ? "You already watch that person" : "You do not watch that person");
        }
        if (watched) {
            try {
                watches.saveAndFlush(new Watch(sheetId, person.getId(), user));
            } catch (DataIntegrityViolationException raced) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "You already watch that person");
            }
        } else {
            watches.delete(existing.get());
            watches.flush();
        }
        audit.recordChangeOnCommit(sheetId, user, (watched ? "PERSON_WATCHED" : "PERSON_UNWATCHED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName());
        return watched;
    }

    /** Tell each user watching the person that one of their claims was settled, in the order they started
     *  watching. */
    public void notifyWatchers(String sheetId, String user, Person person, String number) {
        for (Watch w : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + sheetId + " by=" + user + " user=" + w.getUserName()
                    + " person=" + person.getName() + " number=" + number);
        }
    }
}
