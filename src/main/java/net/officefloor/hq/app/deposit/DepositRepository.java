package net.officefloor.hq.app.deposit;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DepositRepository extends JpaRepository<Deposit, Long> {

    /** A client's deposits, oldest first. */
    List<Deposit> findByClientIdOrderByDateAscIdAsc(Long clientId);

    /** Total a client has paid in deposits (zero when none). */
    @Query("SELECT COALESCE(SUM(d.amount), 0) FROM Deposit d WHERE d.clientId = :clientId")
    BigDecimal sumAmountByClientId(Long clientId);
}
