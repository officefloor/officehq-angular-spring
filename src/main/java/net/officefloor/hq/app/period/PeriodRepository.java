package net.officefloor.hq.app.period;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    /** The sheet's periods, the earliest first. */
    List<Period> findBySheetIdOrderByFromAsc(String sheetId);

    /** Whether a period on the sheet has this name, ignoring case. */
    @Query("select count(p) > 0 from Period p where p.sheetId = :sheetId and lower(p.name) = lower(:name)")
    boolean nameInUse(@Param("sheetId") String sheetId, @Param("name") String name);
}
