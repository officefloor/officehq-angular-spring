package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** An invoice's payments, oldest first. */
    List<Payment> findByInvoiceIdOrderByDateAscIdAsc(Long invoiceId);

    /** Total paid against an invoice (zero when nothing has been paid). */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.invoiceId = :invoiceId")
    BigDecimal sumAmountByInvoiceId(Long invoiceId);
}
