package net.officefloor.hq.app.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    /** The plans on a sheet in the order they were added. */
    List<Plan> findBySheetIdOrderByIdAsc(String sheetId);

    /** A person's plans in the order they were added. */
    List<Plan> findByPersonIdOrderByIdAsc(Long personId);
}
