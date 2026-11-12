package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two snapshot dates compared: for each person in list order, {@code <name> <then> <now>}, where then is the due-total
 * of the SNAPSHOT written when the sheet's today was changed away from the from-date and now that of the to-date, or the
 * person's due-total now when there is no to-date. A figure with no SNAPSHOT is empty; each line is trimmed. It records
 * nothing.
 */
@Service
public class SnapshotCompareService {

    private final PersonService people;
    private final PersonSnapshotRepository snapshots;
    private final RollupService rollup;

    public SnapshotCompareService(PersonService people, PersonSnapshotRepository snapshots, RollupService rollup) {
        this.people = people;
        this.snapshots = snapshots;
        this.rollup = rollup;
    }

    @Transactional(readOnly = true)
    public List<String> compare(String sheetId, LocalDate from, LocalDate to) {
        List<PersonSnapshot> all = snapshots.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, String> then = dueTotalsOn(all, from);
        Map<Long, String> now = to != null ? dueTotalsOn(all, to) : currentDueTotals(sheetId);
        return people.list(sheetId).stream()
                .map(p -> (p.getName() + " " + then.getOrDefault(p.getId(), "") + " "
                        + now.getOrDefault(p.getId(), "")).trim())
                .toList();
    }

    /** Each person's due-total in the latest SNAPSHOT written on leaving the day; none when the day is null. */
    private static Map<Long, String> dueTotalsOn(List<PersonSnapshot> all, LocalDate day) {
        Map<Long, String> totals = new HashMap<>();
        if (day != null) {
            all.stream().filter(s -> s.getDay().equals(day)).forEach(s -> totals.put(s.getPersonId(), s.getDueTotal()));
        }
        return totals;
    }

    private Map<Long, String> currentDueTotals(String sheetId) {
        Map<Long, String> totals = new HashMap<>();
        rollup.snapshot(sheetId).people().forEach((id, figures) -> totals.put(id, figures.dueTotal()));
        return totals;
    }
}
