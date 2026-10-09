package net.officefloor.hq.app.task;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /** A project's tasks in the order they were added. */
    List<Task> findByProjectIdOrderById(Long projectId);

    Optional<Task> findByIdAndProjectId(Long id, Long projectId);
}
