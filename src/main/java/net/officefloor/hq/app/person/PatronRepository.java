package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatronRepository extends JpaRepository<Patron, Long> {

    /** The sheet's patrons, in the order they were named. */
    List<Patron> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsByPersonIdAndPatronId(Long personId, Long patronId);
}
