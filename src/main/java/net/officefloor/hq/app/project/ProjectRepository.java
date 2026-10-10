package net.officefloor.hq.app.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** All projects with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client ORDER BY p.sortOrder, p.id")
    List<Project> findAllWithClient();

    /** All projects not archived, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client WHERE p.archived = false ORDER BY p.sortOrder, p.id")
    List<Project> findActiveWithClient();

    /** All projects carrying a tag, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client JOIN p.tags t WHERE t.id = :tagId ORDER BY p.sortOrder, p.id")
    List<Project> findAllByTagIdWithClient(Long tagId);

    /** All projects not archived carrying a tag, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client JOIN p.tags t WHERE t.id = :tagId"
            + " AND p.archived = false ORDER BY p.sortOrder, p.id")
    List<Project> findActiveByTagIdWithClient(Long tagId);

    /** A client's projects not archived, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client c WHERE c.id = :clientId AND p.archived = false"
            + " ORDER BY p.sortOrder, p.id")
    List<Project> findActiveByClientIdWithClient(Long clientId);

    /** All of a client's projects, archived or not, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client c WHERE c.id = :clientId ORDER BY p.sortOrder, p.id")
    List<Project> findAllByClientIdWithClient(Long clientId);

    /**
     * Projects not archived whose name contains the text, ignoring case, with their client loaded in the
     * same query.
     */
    @Query("SELECT p FROM Project p JOIN FETCH p.client WHERE p.archived = false"
            + " AND LOWER(p.name) LIKE LOWER(CONCAT('%', :text, '%')) ESCAPE '\\' ORDER BY p.sortOrder, p.id")
    List<Project> searchActiveByNameWithClient(String text);

    /** The projects not archived at the given status, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client WHERE p.archived = false AND p.status = :status"
            + " ORDER BY p.sortOrder, p.id")
    List<Project> findActiveByStatusWithClient(ProjectStatus status);

    /** One project with its client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client WHERE p.id = :id")
    Optional<Project> findByIdWithClient(Long id);

    /** The highest position any project holds, or 0 when there are none. */
    @Query("SELECT COALESCE(MAX(p.sortOrder), 0) FROM Project p")
    int maxSortOrder();

    /** All projects, archived or not, in their kept order. */
    @Query("SELECT p FROM Project p ORDER BY p.sortOrder, p.id")
    List<Project> findAllOrdered();

    /** Whether any project, archived or not, already has the code. */
    boolean existsByCode(String code);

    /** How many projects a client has. */
    long countByClientId(Long clientId);

    /** Whether any invoice has been raised against the project. */
    @Query("SELECT COUNT(i) > 0 FROM Invoice i WHERE i.project.id = :id")
    boolean hasInvoices(Long id);
}
