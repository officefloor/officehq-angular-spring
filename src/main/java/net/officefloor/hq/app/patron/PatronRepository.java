package net.officefloor.hq.app.patron;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatronRepository extends JpaRepository<Patron, Long> {

    /** The patrons named on a sheet in the order they were named. */
    List<Patron> findBySheetIdOrderByIdAsc(String sheetId);

    /** Whether the person has already named the patron. */
    boolean existsByPersonIdAndPatronId(Long personId, Long patronId);
}
