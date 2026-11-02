package net.officefloor.hq.app.rate;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRevisionRepository extends JpaRepository<RateRevision, Long> {

    /** The rate revisions of a sheet in the order they were recorded. */
    List<RateRevision> findBySheetIdOrderByIdAsc(String sheetId);
}
