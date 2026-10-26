package net.officefloor.hq.app.asof;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SheetSnapshotRepository extends JpaRepository<SheetSnapshot, Long> {

    /** The digests of the sheet's states, latest first. */
    @Query("SELECT s.digest FROM SheetSnapshot s WHERE s.sheetId = :sheetId ORDER BY s.id DESC")
    List<String> latestDigests(@Param("sheetId") String sheetId, Limit limit);

    /** The ids of the sheet's states stored while its today was on or before the day, latest first. */
    @Query("SELECT s.id FROM SheetSnapshot s WHERE s.sheetId = :sheetId AND s.madeOn <= :day ORDER BY s.id DESC")
    List<Long> idsMadeBy(@Param("sheetId") String sheetId, @Param("day") LocalDate day, Limit limit);
}
