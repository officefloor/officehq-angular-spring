package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** An invoice's payments, oldest first. */
    List<Payment> findByInvoiceIdOrderByDateAscIdAsc(Long invoiceId);

    /** Total paid against an invoice (zero when nothing has been paid). */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.invoiceId = :invoiceId")
    BigDecimal sumAmountByInvoiceId(Long invoiceId);

    /** Total paid against every invoice with any of the given statuses (zero when nothing has been paid). */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p, Invoice i"
            + " WHERE p.invoiceId = i.id AND i.status IN :statuses")
    BigDecimal sumAmountByInvoiceStatusIn(Collection<InvoiceStatus> statuses);

    /** Total paid against invoices with any of the given statuses, per client; clients with none are left out. */
    @Query("SELECT pr.client.id AS clientId, SUM(p.amount) AS total FROM Payment p, Invoice i JOIN i.project pr"
            + " WHERE p.invoiceId = i.id AND i.status IN :statuses GROUP BY pr.client.id")
    List<InvoiceRepository.ClientTotal> sumAmountByInvoiceStatusInPerClient(Collection<InvoiceStatus> statuses);

    /** Total paid against each of the given invoices; invoices with no payments are left out. */
    @Query("SELECT p.invoiceId AS invoiceId, SUM(p.amount) AS paid FROM Payment p"
            + " WHERE p.invoiceId IN :invoiceIds GROUP BY p.invoiceId")
    List<InvoicePaidTotal> sumAmountByInvoiceIds(Collection<Long> invoiceIds);

    /** How much has been paid against one invoice. */
    interface InvoicePaidTotal {
        Long getInvoiceId();

        BigDecimal getPaid();
    }
}
