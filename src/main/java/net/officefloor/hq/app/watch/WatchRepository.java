package net.officefloor.hq.app.watch;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchRepository extends JpaRepository<Watch, Long> {

    /** The people a user watches on a sheet, in the order they started watching. */
    List<Watch> findBySheetIdAndUserOrderByIdAsc(String sheetId, String user);

    /** The users watching a person, in the order they started watching. */
    List<Watch> findByPersonIdOrderByIdAsc(Long personId);

    Optional<Watch> findByPersonIdAndUser(Long personId, String user);
}
