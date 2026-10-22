package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.Audit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Users watching people on a sheet: each user starts and stops watching a person for themselves, and
 * every watcher of a person is notified when one of the person's claims is settled, approved or
 * re-settled.
 */
@Service
public class WatchService {

    private final WatchRepository watches;
    private final PersonRepository people;
    private final Audit audit;

    public WatchService(WatchRepository watches, PersonRepository people, Audit audit) {
        this.watches = watches;
        this.people = people;
        this.audit = audit;
    }

    /** The ids of the people on the sheet the user watches, in ascending order. */
    @Transactional(readOnly = true)
    public List<Long> watched(String sheetId, String user) {
        return watches.findBySheetIdAndUser(sheetId, user).stream().map(Watch::getPersonId).sorted().toList();
    }

    /** Start watching a person as the user; refused when they already watch them. */
    @Transactional
    public void watch(String sheetId, String user, long personId) {
        Person person = person(sheetId, personId);
        if (watches.findBySheetIdAndPersonIdAndUser(sheetId, personId, user).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already watch " + person.getName() + ".");
        }
        watches.saveAndFlush(new Watch(sheetId, personId, user));
        audit.recordOnCommit("PERSON_WATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
    }

    /** Stop watching a person as the user; refused when they do not watch them. */
    @Transactional
    public void unwatch(String sheetId, String user, long personId) {
        Person person = person(sheetId, personId);
        Watch watch = watches.findBySheetIdAndPersonIdAndUser(sheetId, personId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "You do not watch " + person.getName() + "."));
        watches.delete(watch);
        watches.flush();
        audit.recordOnCommit("PERSON_UNWATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
    }

    /**
     * Notify each user watching the person that their claim of this number was settled, approved or
     * re-settled, in the order they started watching, once the change is committed.
     */
    public void notifyWatchers(String sheetId, String user, Person person, String number) {
        for (Watch watch : watches.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + sheetId + " by=" + user + " user=" + watch.getUser()
                    + " person=" + person.getName() + " number=" + number);
        }
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
    }
}
