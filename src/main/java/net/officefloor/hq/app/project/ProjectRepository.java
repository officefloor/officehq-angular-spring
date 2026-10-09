package net.officefloor.hq.app.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** All projects with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client ORDER BY p.id")
    List<Project> findAllWithClient();

    /** One project with its client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client WHERE p.id = :id")
    Optional<Project> findByIdWithClient(Long id);
}
