package net.officefloor.hq.app.snapshot;

import java.time.LocalDate;
import java.util.List;
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

    /** The sheet's snapshots, oldest first; with an as-of date, only those of days before it (a day's
     *  line is written as the sheet's today leaves it, so it had not been written as the day stood). */
    @Transactional(readOnly = true)
    public List<PersonSnapshot> list(String sheetId, LocalDate asOf) {
        return snapshots.findBySheetIdOrderByDayAscIdAsc(sheetId).stream()
                .filter(s -> asOf == null || s.getDay().isBefore(asOf))
                .toList();
    }

    /** Record the person's due-total on the day being left. */
    @Transactional
    public void record(String sheetId, Long personId, LocalDate day, String dueTotal) {
        snapshots.save(new PersonSnapshot(sheetId, personId, day, dueTotal));
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
