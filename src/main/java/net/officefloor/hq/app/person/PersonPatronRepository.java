package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonPatronRepository extends JpaRepository<PersonPatron, Long> {

    List<PersonPatron> findBySheetIdOrderByIdAsc(String sheetId);

    List<PersonPatron> findByPersonIdOrderByIdAsc(Long personId);

    boolean existsByPersonIdAndPatronId(Long personId, Long patronId);
}
