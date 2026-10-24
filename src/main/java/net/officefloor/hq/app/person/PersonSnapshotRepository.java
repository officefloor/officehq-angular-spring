package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonSnapshotRepository extends JpaRepository<PersonSnapshot, Long> {

    /** The sheet's snapshots in the order they were written. */
    List<PersonSnapshot> findBySheetIdOrderByIdAsc(String sheetId);

    /** The sheet's snapshots of the days before {@code date}, in the order they were written. */
    List<PersonSnapshot> findBySheetIdAndDayBeforeOrderByIdAsc(String sheetId, LocalDate date);
}
