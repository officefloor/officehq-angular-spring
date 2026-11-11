package net.officefloor.hq.app.person;

import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Users watching people on a sheet, each user for themselves. A watcher is told, by one NOTIFY record each, whenever a
 * claim of a person they watch is settled, approved or re-settled.
 */
@Service
public class WatchService {

    private final PersonRepository people;
    private final PersonWatchRepository watches;
    private final Audit audit;

    public WatchService(PersonRepository people, PersonWatchRepository watches, Audit audit) {
        this.people = people;
        this.watches = watches;
        this.audit = audit;
    }

    /** The ids of the people the user watches on the sheet. */
    public Set<Long> watchedBy(String sheetId, String user) {
        return watches.findBySheetIdAndUserName(sheetId, user).stream().map(PersonWatch::getPersonId)
                .collect(Collectors.toSet());
    }

    public boolean watches(Long personId, String user) {
        return watches.existsByPersonIdAndUserName(personId, user);
    }

    /** Start (on) or stop watching the person as the user; already so, nothing changes. */
    @Transactional
    public Person watch(String sheetId, String user, Long id, boolean on) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        if (on) {
            if (watches.existsByPersonIdAndUserName(person.getId(), user)) {
                return person;
            }
            try {
                watches.saveAndFlush(new PersonWatch(sheetId, person.getId(), user));
            } catch (DataIntegrityViolationException e) {
                // The same user started watching from another window at the same moment.
                return person;
            }
            audit.recordOnCommit("PERSON_WATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        } else {
            PersonWatch watch = watches.findByPersonIdAndUserName(person.getId(), user).orElse(null);
            if (watch == null) {
                return person;
            }
            watches.delete(watch);
            watches.flush();
            audit.recordOnCommit("PERSON_UNWATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        }
        return person;
    }

    /** Tell each of the person's watchers, in the order they started watching, of a change to the numbered claim. */
    public void notifyWatchers(String sheetId, String user, Person person, String number) {
        for (PersonWatch watch : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + sheetId + " by=" + user + " user=" + watch.getUserName()
                    + " person=" + person.getName() + " number=" + number);
        }
    }
}
