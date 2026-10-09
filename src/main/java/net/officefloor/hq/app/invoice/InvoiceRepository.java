package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByProjectIdOrderById(Long projectId);

    /** Sum of the amounts of all invoices with the given status (zero when there are none). */
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM Invoice i WHERE i.status = :status")
    BigDecimal sumAmountByStatus(InvoiceStatus status);
}
