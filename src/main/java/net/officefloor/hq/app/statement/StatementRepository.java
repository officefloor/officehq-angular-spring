package net.officefloor.hq.app.statement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** The statements on a sheet in the order they were taken. */
    List<Statement> findBySheetIdOrderByIdAsc(String sheetId);

    /** A person's statements in the order they were taken. */
    List<Statement> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);
}
