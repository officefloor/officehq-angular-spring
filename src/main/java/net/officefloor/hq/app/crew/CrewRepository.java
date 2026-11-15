package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewRepository extends JpaRepository<Crew, Long> {

    /** The sheet's crews in the order they were added. */
    List<Crew> findBySheetIdOrderByIdAsc(String sheetId);

    /** The sheet's crew with this name, ignoring case. */
    Optional<Crew> findBySheetIdAndNameIgnoreCase(String sheetId, String name);

    /** The crews the person leads. */
    List<Crew> findByLeadIdOrderByIdAsc(Long leadId);
}
