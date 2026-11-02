package net.officefloor.hq.app.period;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRevisionRepository extends JpaRepository<PeriodRevision, Long> {

    /** The period revisions of a sheet in the order they were recorded. */
    List<PeriodRevision> findBySheetIdOrderByIdAsc(String sheetId);
}
