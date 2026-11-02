package net.officefloor.hq.app.statement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** The statements on a sheet in the order they were taken, cancelled ones too. */
    List<Statement> findBySheetIdOrderByIdAsc(String sheetId);

    /** The statements on a sheet that stand (none cancelled) in the order they were taken. */
    List<Statement> findBySheetIdAndCancelledOnIsNullOrderByIdAsc(String sheetId);

    /** A person's statements that stand (none cancelled) in the order they were taken. */
    List<Statement> findBySheetIdAndPersonIdAndCancelledOnIsNullOrderByIdAsc(String sheetId, Long personId);

    /** A person's statements in the order they were taken, cancelled ones too. */
    List<Statement> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);
}
