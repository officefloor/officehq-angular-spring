package net.officefloor.hq.app.person;

import java.time.LocalDate;
import net.officefloor.hq.app.rate.GradeService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * A steward merges a grade into another: the merge is recorded, then everyone whose grade in force on the
 * sheet's today is the merged grade is given the other from that today, in list order. Each person is edited
 * on their own, exactly as the person form would (their name and own terms kept, no version check), so one
 * that cannot be edited is left as they are while the rest go through. Once all are done, the figures the
 * merge as a whole moved are announced.
 */
@Service
public class GradeMergeService {

    public record Result(int merged) {
    }

    private final PersonService people;
    private final GradeService grades;
    private final SheetService sheets;
    private final Rollup rollup;
    private final TransactionTemplate tx;

    public GradeMergeService(PersonService people, GradeService grades, SheetService sheets, Rollup rollup,
            TransactionTemplate tx) {
        this.rollup = rollup;
        this.tx = tx;
        this.people = people;
        this.grades = grades;
        this.sheets = sheets;
    }

    public Result merge(String sheetId, String user, String from, String into) {
        Rollup.Figures before = tx.execute(status -> {
            grades.merged(sheetId, user, from, into);
            return rollup.figures(sheetId);
        });
        LocalDate today = sheets.today(sheetId);
        int merged = 0;
        for (Person p : people.list(sheetId)) {
            if (!from.equals(p.gradeOn(today))) {
                continue;
            }
            try {
                people.edit(sheetId, user, p.getId(), null, p.getName(), into, today, p.getTerms(), p.getContact());
                merged++;
            } catch (ResponseStatusException refused) {
                // refused: left as they are
            }
        }
        tx.executeWithoutResult(status -> rollup.changed(sheetId, user, before));
        return new Result(merged);
    }
}
