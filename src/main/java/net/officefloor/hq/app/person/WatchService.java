package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who watches whom on a sheet: each user watches people of their own choosing, and every user watching a
 * person is notified, in the order they started watching, when one of that person's claims is settled,
 * approved or re-settled.
 */
@Service
public class WatchService {

    private final WatchRepository watches;
    private final PersonRepository people;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;

    public WatchService(WatchRepository watches, PersonRepository people, SheetRepository sheetRows,
            ApplicationEventPublisher events) {
        this.watches = watches;
        this.people = people;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    /** The ids of the people the user watches on the sheet. */
    @Transactional(readOnly = true)
    public List<Long> watched(String sheetId, String user) {
        return watches.findBySheetIdAndUserOrderByIdAsc(sheetId, user).stream().map(Watch::getPersonId).toList();
    }

    /**
     * Start (on) or stop watching the person as the user. Starting to watch a person already watched, or
     * stopping when not watching, is refused and nothing changes. Takes turns with other changes on the sheet.
     */
    @Transactional
    public List<Long> watch(String sheetId, String user, long personId, boolean on) {
        sheetRows.lockById(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        var existing = watches.findByPersonIdAndUser(personId, user);
        if (on == existing.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    on ? "Already watching " + person.getName() : "Not watching " + person.getName());
        }
        if (on) {
            watches.saveAndFlush(new Watch(sheetId, personId, user));
        } else {
            watches.delete(existing.get());
            watches.flush();
        }
        events.publishEvent(new PersonWatched(on ? "PERSON_WATCHED" : "PERSON_UNWATCHED", sheetId, user,
                person.getName()));
        return watched(sheetId, user);
    }

    /** Notify each user watching the person, in the order they started watching, of a change to their claim. */
    public void notifyWatchers(String sheetId, String user, Person person, String number) {
        if (person == null) {
            return;
        }
        for (Watch w : watches.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, person.getId())) {
            events.publishEvent(new WatcherNotified(sheetId, user, w.getUser(), person.getName(), number));
        }
    }
}
