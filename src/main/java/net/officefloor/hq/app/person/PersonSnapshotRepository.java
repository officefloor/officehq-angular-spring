package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonSnapshotRepository extends JpaRepository<PersonSnapshot, Long> {

    /** The sheet's snapshot lines in the order they were written. */
    List<PersonSnapshot> findBySheetIdOrderByIdAsc(String sheetId);

    /** The person's snapshot lines in the order they were written. */
    List<PersonSnapshot> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);
}
