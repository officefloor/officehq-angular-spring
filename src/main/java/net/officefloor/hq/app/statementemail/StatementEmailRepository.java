package net.officefloor.hq.app.statementemail;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementEmailRepository extends JpaRepository<StatementEmail, Long> {

    /** When the client's statement was emailed, newest first. */
    List<StatementEmail> findByClientIdOrderBySentAtDescIdDesc(Long clientId);
}
