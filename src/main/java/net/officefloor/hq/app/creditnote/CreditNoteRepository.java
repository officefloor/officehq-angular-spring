package net.officefloor.hq.app.creditnote;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import net.officefloor.hq.app.invoice.InvoiceRepository;
import net.officefloor.hq.app.invoice.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {

    /** An invoice's credit notes, oldest first. */
    List<CreditNote> findByInvoiceIdOrderByIdAsc(Long invoiceId);

    /** Total credited against an invoice (zero when nothing has been credited). */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CreditNote c WHERE c.invoiceId = :invoiceId")
    BigDecimal sumAmountByInvoiceId(Long invoiceId);

    /** Total credited against invoices with any of the given statuses, per client; clients with none are left out. */
    @Query("SELECT pr.client.id AS clientId, SUM(c.amount) AS total FROM CreditNote c, Invoice i JOIN i.project pr"
            + " WHERE c.invoiceId = i.id AND i.status IN :statuses GROUP BY pr.client.id")
    List<InvoiceRepository.ClientTotal> sumAmountByInvoiceStatusInPerClient(Collection<InvoiceStatus> statuses);

    /** Total credited against each of the given invoices; invoices with no credit notes are left out. */
    @Query("SELECT c.invoiceId AS invoiceId, SUM(c.amount) AS credited FROM CreditNote c"
            + " WHERE c.invoiceId IN :invoiceIds GROUP BY c.invoiceId")
    List<InvoiceCreditedTotal> sumAmountByInvoiceIds(Collection<Long> invoiceIds);

    /** How much has been credited against one invoice. */
    interface InvoiceCreditedTotal {
        Long getInvoiceId();

        BigDecimal getCredited();
    }
}
