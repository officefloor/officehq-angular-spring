package net.officefloor.hq.app.deposit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositRepository extends JpaRepository<Deposit, Long> {

    /** A client's deposits, oldest first. */
    List<Deposit> findByClientIdOrderByDateAscIdAsc(Long clientId);
}
