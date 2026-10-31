package net.officefloor.hq.app.watch;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchRepository extends JpaRepository<Watch, Long> {

    List<Watch> findBySheetIdAndUserNameOrderByIdAsc(String sheetId, String userName);

    List<Watch> findByPersonIdOrderByIdAsc(Long personId);

    List<Watch> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Watch> findByPersonIdAndUserName(Long personId, String userName);
}
