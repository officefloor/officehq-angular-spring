package net.officefloor.hq.app.snapshot;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The snapshot lines written for people as the sheet's today changes. */
@Service
public class PersonSnapshotService {

    private final PersonSnapshotRepository snapshots;

    public PersonSnapshotService(PersonSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /** The sheet's snapshots, oldest first; with an as-of date, only those written by then: a day's line is
     *  written as the sheet's today leaves it for another, so it had not been written while the sheet stood on that
     *  day, nor before it reached the today it moved to. */
    @Transactional(readOnly = true)
    public List<PersonSnapshot> list(String sheetId, LocalDate asOf) {
        return snapshots.findBySheetIdOrderByDayAscIdAsc(sheetId).stream()
                .filter(s -> asOf == null || s.writtenBy(asOf))
                .toList();
    }

    /** Each person's due-total in the snapshot line written when the sheet's today left the day, by person id: the
     *  latest such line when it was left more than once; a person with none is missing. */
    @Transactional(readOnly = true)
    public Map<Long, String> on(String sheetId, LocalDate day) {
        Map<Long, String> dueTotals = new HashMap<>();
        for (PersonSnapshot s : snapshots.findBySheetIdOrderByDayAscIdAsc(sheetId)) {
            if (s.getDay().equals(day)) {
                dueTotals.put(s.getPersonId(), s.getDueTotal());
            }
        }
        return dueTotals;
    }

    /** Record the person's due-total on the day being left for the sheet's new today. */
    @Transactional
    public void record(String sheetId, Long personId, LocalDate day, String dueTotal, LocalDate today) {
        snapshots.save(new PersonSnapshot(sheetId, personId, day, dueTotal, today));
    }

    /** Move the person's snapshots to another person, on another sheet (a merge or transfer); a day the other
     *  already has a snapshot line for keeps theirs, and the moved one is dropped. */
    @Transactional
    public void move(String fromSheetId, Long fromPersonId, String toSheetId, Long toPersonId) {
        Set<LocalDate> theirs = snapshots.findBySheetIdAndPersonIdOrderByIdAsc(toSheetId, toPersonId).stream()
                .map(PersonSnapshot::getDay)
                .collect(Collectors.toSet());
        for (PersonSnapshot s : snapshots.findBySheetIdAndPersonIdOrderByIdAsc(fromSheetId, fromPersonId)) {
            if (theirs.contains(s.getDay())) {
                snapshots.delete(s);
            } else {
                s.moveTo(toSheetId, toPersonId);
            }
        }
        snapshots.flush();
    }
}
