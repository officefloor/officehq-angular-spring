package net.officefloor.hq.app.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanChangeRepository extends JpaRepository<PlanChange, Long> {

    List<PlanChange> findBySheetIdOrderByIdAsc(String sheetId);

    List<PlanChange> findByPlanIdAndSheetIdOrderByIdAsc(Long planId, String sheetId);
}
