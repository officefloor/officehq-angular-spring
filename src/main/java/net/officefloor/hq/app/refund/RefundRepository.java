package net.officefloor.hq.app.refund;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    /** A client's refunds, newest first. */
    List<Refund> findByClientIdOrderByDateDescIdDesc(Long clientId);

    /** Total refunded to a client (zero when none). */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.clientId = :clientId")
    BigDecimal sumAmountByClientId(Long clientId);

    /** Total refunded to a client out of their held deposits (zero when none). */
    @Query("SELECT COALESCE(SUM(r.fromDeposits), 0) FROM Refund r WHERE r.clientId = :clientId")
    BigDecimal sumFromDepositsByClientId(Long clientId);

    /** Total refunded to a client out of their unused credit notes (zero when none). */
    @Query("SELECT COALESCE(SUM(r.fromCreditNotes), 0) FROM Refund r WHERE r.clientId = :clientId")
    BigDecimal sumFromCreditNotesByClientId(Long clientId);
}
