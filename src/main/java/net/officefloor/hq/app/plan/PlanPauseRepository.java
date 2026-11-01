package net.officefloor.hq.app.plan;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanPauseRepository extends JpaRepository<PlanPause, Long> {

    List<PlanPause> findByPlanIdInOrderByIdAsc(Collection<Long> planIds);
}
