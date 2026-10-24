package net.officefloor.hq.app.period;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    List<Period> findBySheetIdOrderByFromAsc(String sheetId);

    Optional<Period> findBySheetIdAndNameKey(String sheetId, String nameKey);

    /** The period, locked until the transaction ends so it is never closed twice at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Period p WHERE p.id = :id AND p.sheetId = :sheetId")
    Optional<Period> lockByIdAndSheetId(Long id, String sheetId);

    /** Whether a closed period of the sheet, not only soft-closed, contains the date. */
    @Query("SELECT COUNT(p) > 0 FROM Period p WHERE p.sheetId = :sheetId AND p.closed = TRUE AND p.soft = FALSE"
            + " AND p.from <= :date AND p.to >= :date")
    boolean closedOn(String sheetId, LocalDate date);

    /** Whether a soft-closed period of the sheet contains the date. */
    @Query("SELECT COUNT(p) > 0 FROM Period p WHERE p.sheetId = :sheetId AND p.closed = TRUE AND p.soft = TRUE"
            + " AND p.from <= :date AND p.to >= :date")
    boolean softClosedOn(String sheetId, LocalDate date);
}
