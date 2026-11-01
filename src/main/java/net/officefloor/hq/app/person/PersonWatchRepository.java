package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonWatchRepository extends JpaRepository<PersonWatch, Long> {

    List<PersonWatch> findBySheetIdAndUserName(String sheetId, String userName);

    List<PersonWatch> findByPersonIdOrderByIdAsc(Long personId);

    Optional<PersonWatch> findByPersonIdAndUserName(Long personId, String userName);
}
