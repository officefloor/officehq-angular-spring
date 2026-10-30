package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimTemplateRepository extends JpaRepository<ClaimTemplate, Long> {

    List<ClaimTemplate> findBySheetIdOrderByNameAsc(String sheetId);

    Optional<ClaimTemplate> findBySheetIdAndName(String sheetId, String name);
}
