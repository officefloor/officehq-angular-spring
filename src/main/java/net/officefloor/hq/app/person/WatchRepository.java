package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchRepository extends JpaRepository<Watch, Long> {

    List<Watch> findBySheetIdAndUserNameOrderByIdAsc(String sheetId, String userName);

    List<Watch> findByPersonIdOrderByIdAsc(Long personId);

    Optional<Watch> findByPersonIdAndUserName(Long personId, String userName);
}
