package net.officefloor.hq.app.sheet;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {

    /** The sheet's policy versions, oldest first. */
    List<PolicyVersion> findBySheetIdOrderByVersionAsc(String sheetId);

    long countBySheetId(String sheetId);
}
