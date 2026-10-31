package net.officefloor.hq.app.history;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SheetSnapshotRepository extends JpaRepository<SheetSnapshot, Long> {

    /** The latest snapshot kept under a day on or before {@code date}: the last one of the latest such day. */
    Optional<SheetSnapshot> findFirstBySheetIdAndAsOfLessThanEqualOrderByAsOfDescIdDesc(String sheetId, LocalDate date);

    /** Drop the snapshots kept under a day, which a newer one of that day replaces. */
    @Transactional
    @Modifying
    @Query("delete from SheetSnapshot s where s.sheetId = :sheetId and s.asOf = :asOf")
    int deleteBySheetIdAndAsOf(@Param("sheetId") String sheetId, @Param("asOf") LocalDate asOf);
}
