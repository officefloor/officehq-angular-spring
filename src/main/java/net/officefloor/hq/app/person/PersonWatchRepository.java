package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonWatchRepository extends JpaRepository<PersonWatch, Long> {

    /** The person's watchers in the order they started watching. */
    List<PersonWatch> findByPersonIdOrderByIdAsc(Long personId);

    /** What the user watches on the sheet. */
    List<PersonWatch> findBySheetIdAndUserName(String sheetId, String userName);

    Optional<PersonWatch> findByPersonIdAndUserName(Long personId, String userName);

    boolean existsByPersonIdAndUserName(Long personId, String userName);
}
