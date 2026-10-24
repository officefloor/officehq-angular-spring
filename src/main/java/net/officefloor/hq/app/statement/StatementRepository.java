package net.officefloor.hq.app.statement;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** The sheet's statements in the order they were taken. */
    List<Statement> findBySheetIdOrderByIdAsc(String sheetId);

    /** The person's last statement. */
    Optional<Statement> findFirstBySheetIdAndPersonIdOrderByIdDesc(String sheetId, Long personId);
}
