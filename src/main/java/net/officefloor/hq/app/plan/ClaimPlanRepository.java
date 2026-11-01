package net.officefloor.hq.app.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimPlanRepository extends JpaRepository<ClaimPlan, Long> {

    List<ClaimPlan> findBySheetIdOrderByIdAsc(String sheetId);
}
