package net.officefloor.hq.app.person;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonMergeRepository extends JpaRepository<PersonMerge, Long> {

    /** The last merge into the person on the sheet not yet undone. */
    Optional<PersonMerge> findFirstBySheetIdAndIntoIdAndUndoneFalseOrderByIdDesc(String sheetId, Long intoId);
}
