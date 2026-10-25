package net.officefloor.hq.app.plan;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    /** The sheet's plans in the order they were added. */
    List<Plan> findBySheetIdOrderByIdAsc(String sheetId);

    /** The plan, locked until the transaction ends, so two generates make its claim for a date once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Plan p where p.id = :id")
    Optional<Plan> findForUpdate(@Param("id") Long id);
}
