package net.officefloor.hq.app.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimRevisionRepository extends JpaRepository<ClaimRevision, Long> {

    /** The claim revisions of a sheet in the order they were recorded. */
    List<ClaimRevision> findBySheetIdOrderByIdAsc(String sheetId);
}
