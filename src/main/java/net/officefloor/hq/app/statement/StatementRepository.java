package net.officefloor.hq.app.statement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** A sheet's statements, oldest first. */
    List<Statement> findBySheetIdOrderByIdAsc(String sheetId);
}
