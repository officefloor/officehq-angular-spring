package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonSnapshotRepository extends JpaRepository<PersonSnapshot, Long> {

    /** The sheet's snapshots, oldest first. */
    List<PersonSnapshot> findBySheetIdOrderByIdAsc(String sheetId);

    /** The person's snapshots, oldest first. */
    List<PersonSnapshot> findByPersonIdOrderByIdAsc(Long personId);
}
