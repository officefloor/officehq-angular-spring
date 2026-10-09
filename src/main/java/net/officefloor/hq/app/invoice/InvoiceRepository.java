package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByProjectIdOrderById(Long projectId);

    /** All invoices across every project, with their project loaded in the same query. */
    @Query("SELECT i FROM Invoice i JOIN FETCH i.project ORDER BY i.id")
    List<Invoice> findAllWithProject();

    /** Invoices across every project at the given status, with their project loaded in the same query. */
    @Query("SELECT i FROM Invoice i JOIN FETCH i.project WHERE i.status = :status ORDER BY i.id")
    List<Invoice> findAllWithProjectByStatus(InvoiceStatus status);

    /** One page of the invoices across every project, with their project loaded in the same query. */
    @Query(value = "SELECT i FROM Invoice i JOIN FETCH i.project ORDER BY i.id",
            countQuery = "SELECT COUNT(i) FROM Invoice i")
    Page<Invoice> findPageWithProject(Pageable pageable);

    /** One page of the invoices at the given status, with their project loaded in the same query. */
    @Query(value = "SELECT i FROM Invoice i JOIN FETCH i.project WHERE i.status = :status ORDER BY i.id",
            countQuery = "SELECT COUNT(i) FROM Invoice i WHERE i.status = :status")
    Page<Invoice> findPageWithProjectByStatus(InvoiceStatus status, Pageable pageable);

    /** A client's invoices across all of their projects, with their project loaded in the same query. */
    @Query("SELECT i FROM Invoice i JOIN FETCH i.project p WHERE p.client.id = :clientId ORDER BY i.id")
    List<Invoice> findByClientIdWithProject(Long clientId);

    /** Sum of the amounts of all invoices with any of the given statuses (zero when there are none). */
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM Invoice i WHERE i.status IN :statuses")
    BigDecimal sumAmountByStatusIn(Collection<InvoiceStatus> statuses);

    /** Sum of the invoice amounts with any of the given statuses, per client; clients with none are left out. */
    @Query("SELECT p.client.id AS clientId, SUM(i.amount) AS total FROM Invoice i JOIN i.project p"
            + " WHERE i.status IN :statuses GROUP BY p.client.id")
    List<ClientTotal> sumAmountByStatusInPerClient(Collection<InvoiceStatus> statuses);

    /** A total of money for one client. */
    interface ClientTotal {
        Long getClientId();

        BigDecimal getTotal();
    }

    /** Number of invoices with any of the given statuses whose due date is before the given date. */
    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.status IN :statuses AND i.dueDate < :date")
    long countByStatusInAndDueDateBefore(Collection<InvoiceStatus> statuses, LocalDate date);
}
