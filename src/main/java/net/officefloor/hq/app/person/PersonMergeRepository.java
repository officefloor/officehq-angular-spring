package net.officefloor.hq.app.person;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonMergeRepository extends JpaRepository<PersonMerge, Long> {

    /** The last merge into the person still standing. */
    Optional<PersonMerge> findFirstBySheetIdAndIntoIdOrderByIdDesc(String sheetId, Long intoId);
}
