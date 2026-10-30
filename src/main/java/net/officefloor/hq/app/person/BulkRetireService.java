package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Set;
import net.officefloor.hq.app.access.AccessService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Retires a selection of people from a date together, in list order: the people in the order they
 * were added. Each person is retired on their own, exactly as the row's retire would (a date left
 * out takes the sheet's today), so one that cannot be retired is left as they are while the rest go
 * through.
 */
@Service
public class BulkRetireService {

    public record Result(int retired) {
    }

    private final PersonService people;
    private final AccessService access;

    public BulkRetireService(PersonService people, AccessService access) {
        this.people = people;
        this.access = access;
    }

    public Result retire(String sheetId, String user, Set<Long> personIds, LocalDate from) {
        if (access.isArchived(sheetId) || access.isViewer(sheetId, user)) {
            return new Result(0); // an archived sheet, or a viewer, changes nothing: every one refused
        }
        int retired = 0;
        for (Person p : people.list(sheetId)) {
            if (!personIds.contains(p.getId())) {
                continue;
            }
            try {
                people.retire(sheetId, user, p.getId(), from);
                retired++;
            } catch (ResponseStatusException refused) {
                // refused: left as they are
            }
        }
        return new Result(retired);
    }
}
