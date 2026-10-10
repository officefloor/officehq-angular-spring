package net.officefloor.hq.app.instalment;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InstalmentRepository extends JpaRepository<Instalment, Long> {

    /** An invoice's instalments, earliest due first. */
    List<Instalment> findByInvoiceIdOrderByDueDateAscIdAsc(Long invoiceId);

    /** Total scheduled across an invoice's instalments (zero when there are none). */
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM Instalment i WHERE i.invoiceId = :invoiceId")
    BigDecimal sumAmountByInvoiceId(Long invoiceId);
}
