package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchRepository extends JpaRepository<Watch, Long> {

    /** The people a user watches on a sheet. */
    List<Watch> findBySheetIdAndUserName(String sheetId, String userName);

    /** A person's watchers, in the order they started watching. */
    List<Watch> findByPersonIdOrderByIdAsc(Long personId);

    Optional<Watch> findByPersonIdAndUserName(Long personId, String userName);
}
