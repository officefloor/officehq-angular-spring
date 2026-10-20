package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchRepository extends JpaRepository<Watch, Long> {

    /** The users watching the person, in the order they started watching. */
    List<Watch> findByPersonIdOrderByIdAsc(Long personId);

    /** The people on the sheet the user watches. */
    List<Watch> findBySheetIdAndUser(String sheetId, String user);

    Optional<Watch> findByPersonIdAndUser(Long personId, String user);
}
