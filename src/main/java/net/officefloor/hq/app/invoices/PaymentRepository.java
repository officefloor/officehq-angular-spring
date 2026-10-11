package net.officefloor.hq.app.invoices;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** The payments with a part allocated to the invoice, oldest first. */
    @Query("SELECT DISTINCT p FROM Payment p LEFT JOIN FETCH p.allocations WHERE p.id IN (SELECT q.id FROM Payment q JOIN q.allocations a WHERE a.invoiceId = :invoiceId) ORDER BY p.date, p.id")
    List<Payment> findForInvoice(@Param("invoiceId") Long invoiceId);

    /** For each of the invoices that has been paid something, its id and the amount paid to it. */
    @Query("SELECT a.invoiceId, SUM(a.amount) FROM Payment p JOIN p.allocations a WHERE a.invoiceId IN :invoiceIds GROUP BY a.invoiceId")
    List<Object[]> paidTo(@Param("invoiceIds") Collection<Long> invoiceIds);
}
