package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonWatchRepository extends JpaRepository<PersonWatch, Long> {

    /** What the user watches on the sheet, in the order they started watching. */
    List<PersonWatch> findBySheetIdAndUserOrderByIdAsc(String sheetId, String user);

    /** Who watches the person, in the order they started watching. */
    List<PersonWatch> findByPersonIdOrderByIdAsc(Long personId);

    Optional<PersonWatch> findByPersonIdAndUser(Long personId, String user);
}
