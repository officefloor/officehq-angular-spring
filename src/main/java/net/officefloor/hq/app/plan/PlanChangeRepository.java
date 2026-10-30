package net.officefloor.hq.app.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanChangeRepository extends JpaRepository<PlanChange, Long> {

    /** The plan's amount changes in the order they were made. */
    List<PlanChange> findByPlanIdOrderByIdAsc(Long planId);
}
