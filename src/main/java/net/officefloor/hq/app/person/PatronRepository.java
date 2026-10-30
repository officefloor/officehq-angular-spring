package net.officefloor.hq.app.person;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatronRepository extends JpaRepository<Patron, Long> {

    List<Patron> findBySheetIdOrderByIdAsc(String sheetId);

    List<Patron> findBySheetIdAndPersonIdOrderByIdAsc(String sheetId, Long personId);

    List<Patron> findBySheetIdAndPatronIdOrderByIdAsc(String sheetId, Long patronId);

    boolean existsByPersonIdAndPatronId(Long personId, Long patronId);
}
