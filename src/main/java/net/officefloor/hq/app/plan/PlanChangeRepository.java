package net.officefloor.hq.app.plan;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanChangeRepository extends JpaRepository<PlanChange, Long> {

    List<PlanChange> findByPlanIdInOrderByIdAsc(Collection<Long> planIds);
}
