package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetCrewRepository extends JpaRepository<SheetCrew, Long> {

    List<SheetCrew> findBySheetIdOrderByIdAsc(String sheetId);

    List<SheetCrew> findByLeadPersonIdOrderByIdAsc(Long leadPersonId);

    boolean existsBySheetIdAndName(String sheetId, String name);

    Optional<SheetCrew> findByIdAndSheetId(Long id, String sheetId);
}
