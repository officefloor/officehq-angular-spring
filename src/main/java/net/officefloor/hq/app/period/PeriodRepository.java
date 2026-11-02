package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The periods on a sheet, earliest first. */
    List<Period> findBySheetIdOrderByFromDateAsc(String sheetId);

    /** Whether the name is in use on the sheet, compared without regard to case. */
    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);

    /** Whether a period on the sheet shares a day with the days from one date to another. */
    @Query("select count(p) > 0 from Period p where p.sheetId = :sheetId and p.fromDate <= :to and p.toDate >= :from")
    boolean overlaps(String sheetId, LocalDate from, LocalDate to);

    /** Whether the day falls inside a closed period on the sheet. */
    @Query("select count(p) > 0 from Period p where p.sheetId = :sheetId and p.closed = true"
            + " and p.fromDate <= :day and p.toDate >= :day")
    boolean closedOn(String sheetId, LocalDate day);

    /** A period on a sheet, locked for the rest of the transaction so two closes or reopens of
     *  it happen one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Period p where p.id = :id and p.sheetId = :sheetId")
    Optional<Period> lockByIdAndSheetId(Long id, String sheetId);
}
