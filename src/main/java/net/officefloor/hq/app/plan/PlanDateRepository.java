package net.officefloor.hq.app.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanDateRepository extends JpaRepository<PlanDate, Long> {

    List<PlanDate> findBySheetId(String sheetId);

    List<PlanDate> findByPlanIdAndSheetIdOrderByIdAsc(Long planId, String sheetId);
}
