package net.officefloor.hq.app.snapshot;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The snapshot lines written for people as the sheet's today changes. */
@Service
public class PersonSnapshotService {

    private final PersonSnapshotRepository snapshots;

    public PersonSnapshotService(PersonSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /** The sheet's snapshots, oldest first; with an as-of date, only those of days on or before it. */
    @Transactional(readOnly = true)
    public List<PersonSnapshot> list(String sheetId, LocalDate asOf) {
        return snapshots.findBySheetIdOrderByDayAscIdAsc(sheetId).stream()
                .filter(s -> asOf == null || !s.getDay().isAfter(asOf))
                .toList();
    }

    /** Record the person's due-total on the day being left. */
    @Transactional
    public void record(String sheetId, Long personId, LocalDate day, String dueTotal) {
        snapshots.save(new PersonSnapshot(sheetId, personId, day, dueTotal));
    }

    /** Move the person's snapshots to another person, on another sheet (a merge or transfer). */
    @Transactional
    public void move(String fromSheetId, Long fromPersonId, String toSheetId, Long toPersonId) {
        snapshots.findBySheetIdAndPersonIdOrderByIdAsc(fromSheetId, fromPersonId)
                .forEach(s -> s.moveTo(toSheetId, toPersonId));
        snapshots.flush();
    }
}
