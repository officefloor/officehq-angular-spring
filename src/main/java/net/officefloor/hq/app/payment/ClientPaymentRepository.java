package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClientPaymentRepository extends JpaRepository<ClientPayment, Long> {

    /** A client's lump payments, newest first. */
    List<ClientPayment> findByClientIdOrderByDateDescIdDesc(Long clientId);

    /** Total of a client's held deposits used up by their lump payments (zero when none). */
    @Query("SELECT COALESCE(SUM(p.fromDeposits), 0) FROM ClientPayment p WHERE p.clientId = :clientId")
    BigDecimal sumFromDepositsByClientId(Long clientId);

    /** Total of a client's unused credit notes used up by their lump payments (zero when none). */
    @Query("SELECT COALESCE(SUM(p.fromCreditNotes), 0) FROM ClientPayment p WHERE p.clientId = :clientId")
    BigDecimal sumFromCreditNotesByClientId(Long clientId);

    /** Total a client has overpaid in lump payments, kept as credit for them (zero when none). */
    @Query("SELECT COALESCE(SUM(p.toCredit), 0) FROM ClientPayment p WHERE p.clientId = :clientId")
    BigDecimal sumToCreditByClientId(Long clientId);
}
