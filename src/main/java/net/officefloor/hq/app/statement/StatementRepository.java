package net.officefloor.hq.app.statement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** The person's statements, the last taken first. */
    List<Statement> findBySheetIdAndPersonIdOrderBySeqDesc(String sheetId, Long personId);
}
