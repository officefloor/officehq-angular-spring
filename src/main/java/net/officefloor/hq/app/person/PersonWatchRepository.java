package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonWatchRepository extends JpaRepository<PersonWatch, Long> {

    /** The people a user watches on a sheet. */
    List<PersonWatch> findBySheetIdAndUser(String sheetId, String user);

    /** A person's watchers in the order they started watching. */
    List<PersonWatch> findByPersonIdOrderByIdAsc(Long personId);

    Optional<PersonWatch> findByPersonIdAndUser(Long personId, String user);
}
