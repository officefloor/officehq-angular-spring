package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewRepository extends JpaRepository<Crew, Long> {

    /** The sheet's crews in the order they were added. */
    List<Crew> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Crew> findBySheetIdAndId(String sheetId, Long id);

    /** The crews the person leads, in the order they were added. */
    List<Crew> findByLeadIdOrderByIdAsc(Long leadId);

    boolean existsBySheetIdAndName(String sheetId, String name);
}
