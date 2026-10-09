package net.officefloor.hq.app.creditnote;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {

    /** An invoice's credit notes, oldest first. */
    List<CreditNote> findByInvoiceIdOrderByIdAsc(Long invoiceId);

    /** Total credited against an invoice (zero when nothing has been credited). */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CreditNote c WHERE c.invoiceId = :invoiceId")
    BigDecimal sumAmountByInvoiceId(Long invoiceId);
}
