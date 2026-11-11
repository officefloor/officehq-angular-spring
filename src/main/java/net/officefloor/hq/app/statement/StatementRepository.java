package net.officefloor.hq.app.statement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** A sheet's statements in the order they were taken. */
    List<Statement> findBySheetIdOrderByIdAsc(String sheetId);

    List<Statement> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);
}
