package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonPatronRepository extends JpaRepository<PersonPatron, Long> {

    /** The sheet's patrons in the order they were named. */
    List<PersonPatron> findBySheetIdOrderByIdAsc(String sheetId);

    List<PersonPatron> findByPersonIdOrderByIdAsc(Long personId);

    /** Who names the patron, in the order they named them. */
    List<PersonPatron> findByPatronIdOrderByIdAsc(Long patronId);

    boolean existsByPersonIdAndPatronId(Long personId, Long patronId);
}
