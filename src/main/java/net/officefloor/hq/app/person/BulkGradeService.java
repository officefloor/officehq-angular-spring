package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gives a selection of people a grade from a date together, in list order: the people in the order
 * they were added. Each person is edited on their own, exactly as the person form would (their name
 * and own terms kept, no version check), so one that cannot be edited is left as they are while the
 * rest go through.
 */
@Service
public class BulkGradeService {

    public record Result(int graded) {
    }

    private final PersonService people;

    public BulkGradeService(PersonService people) {
        this.people = people;
    }

    public Result grade(String sheetId, String user, Set<Long> personIds, String grade, LocalDate from) {
        int graded = 0;
        for (Person p : people.list(sheetId)) {
            if (!personIds.contains(p.getId())) {
                continue;
            }
            try {
                people.edit(sheetId, user, p.getId(), null, p.getName(), grade, from, p.getTerms());
                graded++;
            } catch (ResponseStatusException refused) {
                // refused: left as they are
            }
        }
        return new Result(graded);
    }
}
