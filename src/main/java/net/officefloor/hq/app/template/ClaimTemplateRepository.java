package net.officefloor.hq.app.template;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimTemplateRepository extends JpaRepository<ClaimTemplate, Long> {

    /** The claim templates of a sheet in the order they were first saved. */
    List<ClaimTemplate> findBySheetIdOrderByIdAsc(String sheetId);

    /** The sheet's template of the name. */
    Optional<ClaimTemplate> findBySheetIdAndName(String sheetId, String name);
}
