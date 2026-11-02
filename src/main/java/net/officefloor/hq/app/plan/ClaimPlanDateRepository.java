package net.officefloor.hq.app.plan;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimPlanDateRepository extends JpaRepository<ClaimPlanDate, Long> {

    List<ClaimPlanDate> findBySheetId(String sheetId);

    List<ClaimPlanDate> findByPlanIdOrderByMadeOnAsc(Long planId);

    boolean existsByPlanIdAndMadeOn(Long planId, LocalDate madeOn);
}
