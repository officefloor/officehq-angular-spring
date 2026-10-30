package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewRepository extends JpaRepository<Crew, Long> {

    List<Crew> findBySheetIdOrderByNameAsc(String sheetId);

    List<Crew> findByLeadId(Long leadId);

    Optional<Crew> findBySheetIdAndName(String sheetId, String name);
}
