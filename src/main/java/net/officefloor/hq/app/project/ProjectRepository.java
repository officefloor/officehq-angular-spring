package net.officefloor.hq.app.project;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** All projects with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client ORDER BY p.id")
    List<Project> findAllWithClient();

    /** All projects not archived, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client WHERE p.archived = false ORDER BY p.id")
    List<Project> findActiveWithClient();

    /** All projects carrying a tag, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client JOIN p.tags t WHERE t.id = :tagId ORDER BY p.id")
    List<Project> findAllByTagIdWithClient(Long tagId);

    /** All projects not archived carrying a tag, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client JOIN p.tags t WHERE t.id = :tagId"
            + " AND p.archived = false ORDER BY p.id")
    List<Project> findActiveByTagIdWithClient(Long tagId);

    /** A client's projects not archived, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client c WHERE c.id = :clientId AND p.archived = false"
            + " ORDER BY p.id")
    List<Project> findActiveByClientIdWithClient(Long clientId);

    /** All of a client's projects, archived or not, with their client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client c WHERE c.id = :clientId ORDER BY p.id")
    List<Project> findAllByClientIdWithClient(Long clientId);

    /** One project with its client loaded in the same query. */
    @Query("SELECT p FROM Project p JOIN FETCH p.client WHERE p.id = :id")
    Optional<Project> findByIdWithClient(Long id);

    /** How many projects a client has. */
    long countByClientId(Long clientId);

    /** Whether any invoice has been raised against the project. */
    @Query("SELECT COUNT(i) > 0 FROM Invoice i WHERE i.project.id = :id")
    boolean hasInvoices(Long id);

    /** Sum of the project's invoices with any of the given statuses (zero when there are none). */
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM Invoice i WHERE i.project.id = :id AND i.status IN :statuses")
    BigDecimal sumInvoiceAmountByStatusIn(Long id, Collection<InvoiceStatus> statuses);
}
