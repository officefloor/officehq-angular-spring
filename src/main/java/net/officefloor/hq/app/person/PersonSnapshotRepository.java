package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonSnapshotRepository extends JpaRepository<PersonSnapshot, Long> {

    /** The sheet's snapshot lines, oldest first. */
    List<PersonSnapshot> findBySheetIdOrderByIdAsc(String sheetId);

    /** The person's snapshot lines, oldest first. */
    List<PersonSnapshot> findByPersonIdOrderByIdAsc(Long personId);
}
