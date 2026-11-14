package net.officefloor.hq.app.plan;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanPauseRepository extends JpaRepository<PlanPause, Long> {

    /** The plans' pauses, in the order they were made. */
    List<PlanPause> findByPlanIdInOrderByIdAsc(Collection<Long> planIds);

    /** The plan's pause still open, if it is paused. */
    Optional<PlanPause> findFirstByPlanIdAndResumedOnIsNull(Long planId);
}
