package net.officefloor.hq.app.statement;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** The sheet's statements in the order they were taken. */
    List<Statement> findBySheetIdOrderByIdAsc(String sheetId);

    /** The person's statements in the order they were taken. */
    List<Statement> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);

    /** The person's statements as they stand, oldest first. */
    default List<Statement> inOrder(String sheetId, Long personId) {
        return findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId).stream().sorted(Statement.ORDER).toList();
    }

    /** The person's last statement: the one placed last among theirs. */
    default Optional<Statement> last(String sheetId, Long personId) {
        return findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId).stream().max(Statement.ORDER);
    }
}
