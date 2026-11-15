package net.officefloor.hq.app.template;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimTemplateRepository extends JpaRepository<ClaimTemplate, Long> {

    /** The sheet's templates, by name. */
    List<ClaimTemplate> findBySheetIdOrderByNameAsc(String sheetId);

    Optional<ClaimTemplate> findBySheetIdAndName(String sheetId, String name);
}
