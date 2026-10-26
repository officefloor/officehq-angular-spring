package net.officefloor.hq.app.crew;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewRepository extends JpaRepository<Crew, Long> {

    /** The sheet's crews in the order they were added. */
    List<Crew> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsBySheetIdAndName(String sheetId, String name);
}
