package net.officefloor.hq.app.statement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** The sheet's statements in the order they were taken. */
    List<Statement> findBySheetIdOrderByIdAsc(String sheetId);

    /** Drop the person's statements. */
    void deleteBySheetIdAndPersonId(String sheetId, Long personId);

    /** The person's statements in the order they were taken. */
    List<Statement> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);
}
