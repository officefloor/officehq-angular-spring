package net.officefloor.hq.app.snapshot;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonSnapshotRepository extends JpaRepository<PersonSnapshot, Long> {

    /** The sheet's snapshots, oldest first. */
    List<PersonSnapshot> findBySheetIdOrderByDayAscIdAsc(String sheetId);

    List<PersonSnapshot> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);
}
