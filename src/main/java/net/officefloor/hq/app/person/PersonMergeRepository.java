package net.officefloor.hq.app.person;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonMergeRepository extends JpaRepository<PersonMerge, Long> {

    /** The last merge made into the person on the sheet, undone or not. */
    Optional<PersonMerge> findFirstBySheetIdAndIntoIdOrderByIdDesc(String sheetId, Long intoId);
}
