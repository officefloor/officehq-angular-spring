package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatronRepository extends JpaRepository<Patron, Long> {

    List<Patron> findBySheetId(String sheetId);

    List<Patron> findByPersonId(Long personId);

    boolean existsByPersonIdAndPatronId(Long personId, Long patronId);
}
