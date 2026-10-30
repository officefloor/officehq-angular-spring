package net.officefloor.hq.app.person;

import net.officefloor.hq.app.sheet.DaySnapshot;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Each person's snapshot lines: as the sheet's today moves on, one is written for everyone a {@code SNAPSHOT}
 * is written for, with the day being left and their due-total on it, in the same change.
 */
@Service
public class PersonSnapshots {

    private final PersonSnapshotRepository snapshots;

    public PersonSnapshots(PersonSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    @EventListener
    public void on(DaySnapshot e) {
        e.people().forEach((personId, p) -> snapshots.save(new PersonSnapshot(e.sheetId(), personId, e.day(), p.dueTotal())));
        snapshots.flush();
    }
}
