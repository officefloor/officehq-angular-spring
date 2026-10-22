package net.officefloor.hq.app.history;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetSnapshotRepository extends JpaRepository<SheetSnapshot, Long> {

    /** Of the snapshots kept for a date on or before this one, the last kept. */
    Optional<SheetSnapshot> findFirstBySheetIdAndAsOfLessThanEqualOrderByIdDesc(String sheetId, LocalDate asOf);
}
