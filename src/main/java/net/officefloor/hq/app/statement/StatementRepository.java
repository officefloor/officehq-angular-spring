package net.officefloor.hq.app.statement;

import java.util.Comparator;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** The sheet's statements, in the order they were taken. */
    List<Statement> findBySheetIdOrderByIdAsc(String sheetId);

    /** A person's statements, in the order they were taken. */
    List<Statement> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);

    /** A person's statements, in their order: the last is their last statement. */
    default List<Statement> inOrder(String sheetId, Long personId) {
        return findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId).stream()
                .sorted(Comparator.comparingLong(Statement::getOrder)).toList();
    }
}
