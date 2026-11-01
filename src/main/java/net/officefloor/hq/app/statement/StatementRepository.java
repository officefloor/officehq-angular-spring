package net.officefloor.hq.app.statement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    List<Statement> findBySheetIdOrderByIdAsc(String sheetId);

    List<Statement> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);
}
