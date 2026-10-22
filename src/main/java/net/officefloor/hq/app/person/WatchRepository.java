package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchRepository extends JpaRepository<Watch, Long> {

    /** The person's watches in the order they were started. */
    List<Watch> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);

    /** The watches the user has on the sheet. */
    List<Watch> findBySheetIdAndUser(String sheetId, String user);

    Optional<Watch> findBySheetIdAndPersonIdAndUser(String sheetId, Long personId, String user);
}
