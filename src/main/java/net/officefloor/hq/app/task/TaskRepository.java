package net.officefloor.hq.app.task;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /** A project's tasks in the order they were added. */
    List<Task> findByProjectIdOrderById(Long projectId);

    /** Every task, grouped by job (in the order jobs were added) then in the order added. */
    List<Task> findAllByOrderByProjectIdAscIdAsc();

    /** How many tasks not yet done were due before the given date. */
    long countByDoneFalseAndDueDateBefore(LocalDate date);

    Optional<Task> findByIdAndProjectId(Long id, Long projectId);
}
