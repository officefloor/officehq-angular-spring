package net.officefloor.hq.app.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findBySheetIdOrderByIdAsc(String sheetId);

    List<Plan> findByPersonIdOrderByIdAsc(Long personId);
}
