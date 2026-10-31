package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Set;
import net.officefloor.hq.app.access.AccessService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Holds a selection of people from a date to an optional date together, in list order: the people in
 * the order they were added. Each person is held on their own, exactly as the row's hold would (a
 * from-date left out takes the sheet's today), so one that cannot be held is left as they are while
 * the rest go through.
 */
@Service
public class BulkHoldService {

    public record Result(int held) {
    }

    private final PersonService people;
    private final AccessService access;

    public BulkHoldService(PersonService people, AccessService access) {
        this.people = people;
        this.access = access;
    }

    public Result hold(String sheetId, String user, Set<Long> personIds, LocalDate from, LocalDate to) {
        if (access.isArchived(sheetId) || access.isViewer(sheetId, user)) {
            return new Result(0); // an archived sheet, or a viewer, changes nothing: every one refused
        }
        int held = 0;
        for (Person p : people.list(sheetId)) {
            if (!personIds.contains(p.getId())) {
                continue;
            }
            try {
                people.hold(sheetId, user, p.getId(), from, to);
                held++;
            } catch (ResponseStatusException refused) {
                // refused: left as they are
            }
        }
        return new Result(held);
    }
}
