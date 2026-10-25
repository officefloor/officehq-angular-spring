package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, Long> {

    /** The sheet's people in the order they were added. */
    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** Whether a person on the sheet, other than the one with this id, has this name key. */
    boolean existsBySheetIdAndNameKeyAndIdNot(String sheetId, String nameKey, Long id);

    boolean existsBySheetIdAndNameKey(String sheetId, String nameKey);
}
