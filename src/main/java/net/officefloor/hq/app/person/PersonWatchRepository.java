package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonWatchRepository extends JpaRepository<PersonWatch, Long> {

    /** The people on the sheet the user watches. */
    List<PersonWatch> findBySheetIdAndUserName(String sheetId, String userName);

    /** The person's watchers, in the order they started watching. */
    List<PersonWatch> findByPersonIdOrderByIdAsc(Long personId);

    boolean existsByPersonIdAndUserName(Long personId, String userName);

    long deleteByPersonIdAndUserName(Long personId, String userName);
}
