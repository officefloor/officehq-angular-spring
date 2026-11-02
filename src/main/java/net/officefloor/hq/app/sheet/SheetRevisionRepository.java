package net.officefloor.hq.app.sheet;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetRevisionRepository extends JpaRepository<SheetRevision, Long> {

    /** The sheet's own revisions in the order they were recorded. */
    List<SheetRevision> findBySheetIdOrderByIdAsc(String sheetId);
}
