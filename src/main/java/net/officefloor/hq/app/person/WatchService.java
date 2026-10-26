package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.Audit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people each user watches on a sheet, and the notices a watched person's settled claims send them. */
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

    /** The ids of the people the user watches on the sheet. */
    @Transactional(readOnly = true)
    public List<Long> watched(String sheetId, String user) {
        return watches.findBySheetIdAndUserNameOrderByIdAsc(sheetId, user).stream().map(Watch::getPersonId).toList();
    }

    /** Starts (on) or stops the user watching the person; a person already in that state is left as they are. */
    @Transactional
    public boolean watch(String user, String sheetId, Long personId, boolean on) {
        Person person = people.findWithLockByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        var existing = watches.findByPersonIdAndUserName(person.getId(), user);
        if (on && existing.isEmpty()) {
            try {
                watches.saveAndFlush(new Watch(sheetId, person.getId(), user));
            } catch (DataIntegrityViolationException e) {
                return true;
            }
            audit.recordOnCommit("PERSON_WATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        } else if (!on && existing.isPresent()) {
            watches.delete(existing.get());
            watches.flush();
            audit.recordOnCommit("PERSON_UNWATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        }
        return on;
    }

    /**
     * Records one notice per user watching the person, in the order they started watching, that the claim with the
     * number was settled; called after the claim's own record and before the rollup's.
     */
    public void notifyWatchers(String by, String sheetId, Person person, String number) {
        for (Watch w : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + sheetId + " by=" + by + " user=" + w.getUserName() + " person="
                    + person.getName() + " number=" + number);
        }
    }
}
