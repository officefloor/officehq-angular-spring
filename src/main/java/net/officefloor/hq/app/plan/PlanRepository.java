package net.officefloor.hq.app.plan;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    /** The sheet's plans in the order they were added. */
    List<Plan> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Plan> findByIdAndSheetId(Long id, String sheetId);
}
