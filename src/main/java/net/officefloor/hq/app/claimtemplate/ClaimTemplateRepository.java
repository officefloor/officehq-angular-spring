package net.officefloor.hq.app.claimtemplate;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimTemplateRepository extends JpaRepository<ClaimTemplate, Long> {

    /** The sheet's claim templates, sorted by name. */
    List<ClaimTemplate> findBySheetIdOrderByNameAsc(String sheetId);

    /** The sheet's template of this name, if any. */
    Optional<ClaimTemplate> findBySheetIdAndName(String sheetId, String name);
}
