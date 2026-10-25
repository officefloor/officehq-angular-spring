package net.officefloor.hq.app.rollup;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonSnapshotRepository extends JpaRepository<PersonSnapshot, Long> {

    /** The sheet's snapshots in the order they were taken. */
    List<PersonSnapshot> findBySheetIdOrderByIdAsc(String sheetId);

    /** The person's snapshots in the order they were taken. */
    List<PersonSnapshot> findByPersonIdOrderByIdAsc(Long personId);
}
