package net.officefloor.hq.app.crew;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewRepository extends JpaRepository<Crew, Long> {

    List<Crew> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Crew> findByIdAndSheetId(Long id, String sheetId);

    boolean existsBySheetIdAndName(String sheetId, String name);
}
