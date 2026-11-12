package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchRepository extends JpaRepository<Watch, Long> {

    /** The user's watches on the sheet. */
    List<Watch> findBySheetIdAndUser(String sheetId, String user);

    /** The person's watchers in the order they started watching. */
    List<Watch> findByPersonIdOrderByIdAsc(Long personId);

    Optional<Watch> findByPersonIdAndUser(Long personId, String user);
}
