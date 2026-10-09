package net.officefloor.hq.app.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** All projects with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client ORDER BY p.id")
    List<Project> findAllWithClient();

    /** All projects not archived, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client WHERE p.archived = false ORDER BY p.id")
    List<Project> findActiveWithClient();

    /** A client's projects not archived, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client c WHERE c.id = :clientId AND p.archived = false"
            + " ORDER BY p.id")
    List<Project> findActiveByClientIdWithClient(Long clientId);

    /** One project with its client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client WHERE p.id = :id")
    Optional<Project> findByIdWithClient(Long id);

    /** How many projects a client has. */
    long countByClientId(Long clientId);

    /** Whether any invoice has been raised against the project. */
    @Query("SELECT COUNT(i) > 0 FROM Invoice i WHERE i.project.id = :id")
    boolean hasInvoices(Long id);
}
