package net.officefloor.hq.app.adjustmentnote;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdjustmentNoteRepository extends JpaRepository<AdjustmentNote, Long> {

    /** An invoice's adjustment notes, oldest first. */
    List<AdjustmentNote> findByInvoiceIdOrderByIdAsc(Long invoiceId);

    /** Net adjustment to an invoice (zero when it has none). */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM AdjustmentNote a WHERE a.invoiceId = :invoiceId")
    BigDecimal sumAmountByInvoiceId(Long invoiceId);
}
