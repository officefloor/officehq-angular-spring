package net.officefloor.hq.app.instalment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InstalmentRepository extends JpaRepository<Instalment, Long> {

    /** An invoice's instalments, earliest due first. */
    List<Instalment> findByInvoiceIdOrderByDueDateAscIdAsc(Long invoiceId);

    /** The earliest-due instalment of an invoice that is still to be paid, if any. */
    Optional<Instalment> findFirstByInvoiceIdAndPaidFalseOrderByDueDateAscIdAsc(Long invoiceId);

    /** Total scheduled across an invoice's instalments (zero when there are none). */
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM Instalment i WHERE i.invoiceId = :invoiceId")
    BigDecimal sumAmountByInvoiceId(Long invoiceId);
}
