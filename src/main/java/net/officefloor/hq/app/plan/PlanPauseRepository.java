package net.officefloor.hq.app.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanPauseRepository extends JpaRepository<PlanPause, Long> {

    List<PlanPause> findBySheetId(String sheetId);

    List<PlanPause> findByPlanIdAndSheetId(Long planId, String sheetId);
}
