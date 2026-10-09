package net.officefloor.hq.app.deposit;

import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DepositApplicationRepository extends JpaRepository<DepositApplication, Long> {

    /** Total of a client's deposits already put toward invoices (zero when none has been). */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM DepositApplication a WHERE a.clientId = :clientId")
    BigDecimal sumAmountByClientId(Long clientId);
}
