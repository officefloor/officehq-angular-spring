package net.officefloor.hq.app.statement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** The sheet's statements, each person's in their order. */
    @Query("SELECT s FROM Statement s WHERE s.sheetId = ?1 ORDER BY COALESCE(s.place, s.id), s.id")
    List<Statement> findBySheetIdInOrder(String sheetId);

    /** The person's statements in their order: the last is their last statement. */
    @Query("SELECT s FROM Statement s WHERE s.sheetId = ?1 AND s.personId = ?2 ORDER BY COALESCE(s.place, s.id), s.id")
    List<Statement> findBySheetIdAndPersonIdInOrder(String sheetId, Long personId);
}
