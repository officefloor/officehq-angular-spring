package net.officefloor.hq.app.plan;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanPauseRepository extends JpaRepository<PlanPause, Long> {

    /** The plan's pauses in the order they were made. */
    List<PlanPause> findByPlanIdOrderByIdAsc(Long planId);

    /** The plan's pause still running, if it is paused. */
    Optional<PlanPause> findByPlanIdAndResumedOnIsNull(Long planId);
}
