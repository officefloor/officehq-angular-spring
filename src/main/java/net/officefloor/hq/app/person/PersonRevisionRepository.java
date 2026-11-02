package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRevisionRepository extends JpaRepository<PersonRevision, Long> {

    /** The person revisions of a sheet in the order they were recorded. */
    List<PersonRevision> findBySheetIdOrderByIdAsc(String sheetId);
}
