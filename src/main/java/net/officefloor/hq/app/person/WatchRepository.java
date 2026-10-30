package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchRepository extends JpaRepository<Watch, Long> {

    List<Watch> findBySheetIdAndUserOrderByIdAsc(String sheetId, String user);

    List<Watch> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);

    Optional<Watch> findByPersonIdAndUser(Long personId, String user);
}
